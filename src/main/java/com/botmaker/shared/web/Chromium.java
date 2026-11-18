// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * <b>One running Chromium</b>, started for BotMaker alone: a fresh profile in a temporary folder, deleted on
 * {@link #close()}, and DevTools on a port the browser picks. Headless by default; {@code show} opens a window. No
 * private display session (spec {@code 2026-11-18-web-discovery-design.md} §1): CDP's input goes into the renderer,
 * never the user's cursor or focus.
 */
public final class Chromium implements AutoCloseable {

    /** The viewport every frame and recording shares. */
    public static final int WIDTH = 1440;
    public static final int HEIGHT = 900;

    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    private final Process process;
    private final Path profile;
    private final Cdp cdp;

    /** Who guards the pages a tab opens, by the opening tab's target id. */
    private final Map<String, Consumer<JsonNode>> openers = new ConcurrentHashMap<>();

    private Chromium(Process process, Path profile, Cdp cdp) {
        this.process = process;
        this.profile = profile;
        this.cdp = cdp;
    }

    /**
     * Every new page is attached paused before it runs, so a tab's guard sees a popup or a {@code target=_blank} tab
     * before its first request; a page no tab claims by its {@code openerId} is let go at once.
     */
    private void attachPages() {
        cdp.browser().on("Target.attachedToTarget", params -> {
            Cdp.Session opened = cdp.session(params.path("sessionId").asText());
            Consumer<JsonNode> guard = openers.get(params.path("targetInfo").path("openerId").asText(""));
            if (guard != null) {
                guard.accept(params);
                return;
            }
            // Let go, not detached: newPage's own attach may be given this same session.
            opened.send("Runtime.runIfWaitingForDebugger", Map.of());
        });
        cdp.browser().call("Target.setAutoAttach", Map.of("autoAttach", true, "waitForDebuggerOnStart", true,
                "flatten", true, "filter", List.of(Map.of("type", "page"))));
    }

    /**
     * Starts {@code binary}.
     *
     * @param show  a window instead of headless
     * @param extra more switches, such as {@code --host-resolver-rules} in tests
     */
    public static Chromium start(Path binary, boolean show, List<String> extra) throws IOException {
        Path profile = Files.createTempDirectory("botmaker-chromium-");
        List<String> command = new ArrayList<>(List.of(binary.toString(),
                "--remote-debugging-port=0",
                "--user-data-dir=" + profile,
                "--no-first-run",
                "--no-default-browser-check",
                "--window-size=" + WIDTH + "," + HEIGHT,
                // Nothing but the page talks to the network: no sync, no component or field-trial fetches.
                "--disable-background-networking",
                "--disable-component-update",
                "--disable-sync",
                "--disable-default-apps",
                "--metrics-recording-only",
                "--password-store=basic",
                "--use-mock-keychain",
                "--mute-audio"));
        if (!show) {
            command.add("--headless=new");
        }
        command.addAll(extra == null ? List.of() : extra);
        command.add("about:blank");
        Path log = profile.resolve("botmaker-chromium.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            URI socket = devTools(process, profile, log);
            Chromium chromium = new Chromium(process, profile, Cdp.connect(socket));
            try {
                chromium.attachPages();
            } catch (RuntimeException e) {
                chromium.cdp.close();
                throw e;
            }
            return chromium;
        } catch (IOException | RuntimeException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            stop(process);
            deleteQuietly(profile);
            throw e;
        }
    }

    /** The browser-wide connection. */
    public Cdp cdp() {
        return cdp;
    }

    /** Opens a new blank tab and attaches to it. */
    public WebPage newPage() {
        String target = cdp.browser().call("Target.createTarget", Map.of("url", "about:blank"))
                .path("targetId").asText();
        return new WebPage(cdp, target, cdp.attach(target), openers);
    }

    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            // Wait for the answer, or for the socket to drop as the browser quits: closing first would abort the
            // socket before the command left it.
            cdp.browser().send("Browser.close", Map.of()).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The process is ended below either way.
        }
        cdp.close();
        stop(process);
        deleteQuietly(profile);
    }

    /** Ends the browser and every helper it started (renderers, GPU, zygote), so none holds the profile. */
    private static void stop(Process process) {
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for the profile's {@code DevToolsActivePort}: its port, then the browser's socket path. */
    private static URI devTools(Process process, Path profile, Path log) throws IOException {
        Path file = profile.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() >= 2 && !lines.get(1).isBlank()) {
                    return URI.create("ws://127.0.0.1:" + lines.get(0).strip() + lines.get(1).strip());
                }
            }
            if (!process.isAlive()) {
                throw new IOException("Chromium ended with " + process.exitValue() + ": " + tail(log));
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted starting Chromium", e);
            }
        }
        throw new IOException("Chromium gave no DevTools port in " + START_TIMEOUT.toSeconds() + " s: " + tail(log));
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join(" | ", lines.subList(Math.max(0, lines.size() - 5), lines.size()));
        } catch (IOException e) {
            return "(no log)";
        }
    }

    static void deleteQuietly(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // A temp folder left behind costs disk; the OS clears its temp folder.
        }
    }
}
