// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.UserDirs;
import com.microsoft.playwright.impl.driver.Driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>BotMaker's own Chromium</b>: the build the pinned Playwright drives, installed by Playwright's own installer into
 * {@code UserDirs.cache()/playwright/} ({@code PLAYWRIGHT_BROWSERS_PATH}), never the user's browser nor Playwright's
 * default folder. Playwright checks what it fetches; a Playwright upgrade installs its own build beside the old.
 */
public final class ChromiumInstall {

    private ChromiumInstall() {}

    /** The environment variable Playwright reads its browsers' folder from. */
    static final String BROWSERS_PATH = "PLAYWRIGHT_BROWSERS_PATH";

    private static final long INSTALL_MINUTES = 15;
    /** A progress line of Playwright's installer: {@code |■■■     | 40% of 170.2 MiB}. */
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})% of ([\\d.]+) MiB");

    /** Where Playwright keeps its browsers for BotMaker, whether or not one is there. */
    public static Path directory() {
        return UserDirs.cache().resolve("playwright");
    }

    /** What every Playwright BotMaker starts is told: its browsers' folder. */
    static Map<String, String> environment() {
        return Map.of(BROWSERS_PATH, directory().toString());
    }

    /**
     * What a Playwright that runs the browser is told: its browsers' folder, and to download none. Started without it,
     * Playwright runs its bare {@code install} on each start, which fetches every browser it knows, not only Chromium;
     * {@link #install} alone downloads, Chromium only.
     */
    static Map<String, String> runEnvironment() {
        return Map.of(BROWSERS_PATH, directory().toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
    }

    /**
     * Whether a Chromium Playwright installed is in {@link #directory()}: a {@code chromium-*} folder its installer
     * finished. It may be an older Playwright's; {@link #install} decides that.
     */
    public static boolean installed() {
        if (!Files.isDirectory(directory())) {
            return false;
        }
        try (Stream<Path> builds = Files.list(directory())) {
            return builds.anyMatch(b -> b.getFileName().toString().startsWith("chromium")
                    && Files.isRegularFile(b.resolve("INSTALLATION_COMPLETE")));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Installs the Chromium this Playwright drives (and its headless shell) when it is not there yet: Playwright's
     * {@code install chromium}, which does nothing for a build already in place. About 300 MB, once per Playwright
     * version. Whether it is installed now.
     */
    public static boolean install(Downloads.Progress progress) {
        try {
            Driver driver = Driver.ensureDriverInstalled(environment(), false);
            ProcessBuilder builder = driver.createProcessBuilder();
            builder.environment().putAll(environment());
            builder.command().add("install");
            builder.command().add("chromium");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            // Read on a thread of its own, so the wait below is what bounds a download that stalls.
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        report(line, progress);
                    }
                } catch (IOException | RuntimeException ended) {
                    // The installer's output closed with it.
                }
            });
            if (!process.waitFor(INSTALL_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return false;
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));
            return process.exitValue() == 0 && installed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Passes on what an installer's line says of its download, in bytes. */
    private static void report(String line, Downloads.Progress progress) {
        Matcher m = PERCENT.matcher(line);
        String last = null;
        String total = null;
        while (m.find()) {
            last = m.group(1);
            total = m.group(2);
        }
        if (last == null) {
            return;
        }
        long bytes = (long) (Double.parseDouble(total) * 1024 * 1024);
        progress.accept(bytes * Integer.parseInt(last) / 100, bytes);
    }
}
