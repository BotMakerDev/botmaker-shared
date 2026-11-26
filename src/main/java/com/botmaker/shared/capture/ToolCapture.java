// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;
import com.botmaker.shared.platform.SessionEnv;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Full-desktop capture under Wayland through a screenshot program, where AWT {@link java.awt.Robot} returns
 * black or asks the portal for permission on every grab.
 *
 * <p>One constant per program, each run with the arguments that make it grab every monitor at once with no
 * picker and write a PNG to the path appended last. Until 2026-09-28 only Spectacle was here, and the SDK's
 * plugin kept its own copy of this list with grim and gnome-screenshot in it, so a GNOME or Sway user got a
 * working desktop grab from one capture path and a black one from the other. There is one list now.
 */
public enum ToolCapture implements CaptureBackend {

    /** KDE Plasma: {@code -f} is the whole desktop with no region picker, {@code -b -n} runs it quietly. */
    SPECTACLE(Executables.SPECTACLE, "-b", "-n", "-f", "-o"),

    /** wlroots compositors (Sway, Hyprland): a bare output path is the whole layout. */
    GRIM(Executables.GRIM),

    /** GNOME: {@code -f} names the output file. */
    GNOME_SCREENSHOT(Executables.GNOME_SCREENSHOT, "-f");

    /** A desktop grab is a fraction of a second; past this the program is stuck. */
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(15);
    /** One grab and one more when the first wrote nothing. */
    private static final int ATTEMPTS = 2;
    private static final Duration RETRY_PAUSE = Duration.ofMillis(300);
    /** Held for a grab, across every program: Spectacle hands a second, concurrent one to the first. */
    private static final Object GRABS = new Object();

    private final String binary;
    private final String[] arguments;

    ToolCapture(String binary, String... arguments) {
        this.binary = binary;
        this.arguments = arguments;
    }

    @Override
    public String binaryName() {
        return binary;
    }

    /**
     * The first program installed on this machine, in declaration order, when running under Wayland; empty
     * otherwise, where {@link RobotCapture} works and needs nothing installed.
     */
    static Optional<ToolCapture> available() {
        if (System.getenv(SessionEnv.WAYLAND_DISPLAY) == null) return Optional.empty();
        return Arrays.stream(values()).filter(tool -> Executables.onPath(tool.binary)).findFirst();
    }

    /** The full argument vector for writing the grab to {@code out}. */
    String[] argv(Path out) {
        String[] argv = new String[arguments.length + 2];
        argv[0] = binary;
        System.arraycopy(arguments, 0, argv, 1, arguments.length);
        argv[argv.length - 1] = out.toString();
        return argv;
    }

    /**
     * The whole desktop, or {@code null}. Never the Robot (2026-11-26): on Wayland the Robot reads pixels through the
     * screen-cast portal, which asks the user on every grab and keeps no "don't ask again". Spectacle is one instance
     * per session, so a grab started while another runs is handed to that one and writes nothing: grabs here go one
     * at a time, and one that wrote nothing is tried once more.
     */
    @Override
    public BufferedImage captureDesktop() {
        synchronized (GRABS) {
            return firstImage(ATTEMPTS, RETRY_PAUSE, this::grabOnce);
        }
    }

    /**
     * The first of up to {@code attempts} calls of {@code attempt} that gives an image, pausing {@code pause} between
     * two; {@code null} when none does.
     */
    static BufferedImage firstImage(int attempts, Duration pause, Supplier<Attempt> attempt) {
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            Attempt tried = attempt.get();
            if (tried.image() != null) return tried.image();
            if (!tried.again() || Thread.currentThread().isInterrupted()) return null;
        }
        return null;
    }

    /**
     * One grab's answer: its image, or none and whether another try may get one — yes when the program finished
     * without writing an image, no when it hung (a second would hang as long, every grab waiting behind it).
     */
    record Attempt(BufferedImage image, boolean again) {
        static Attempt of(BufferedImage image) {
            return new Attempt(image, false);
        }

        static final Attempt EMPTY = new Attempt(null, true);
        static final Attempt FAILED = new Attempt(null, false);
    }

    private Attempt grabOnce() {
        Path out = null;
        try {
            out = Files.createTempFile("botcap", ".png");
            // Drained and bounded: Spectacle's merged stream used to be started and never read, so a chatty
            // build (a Wayland warning, a KDE debug build) filled the pipe and hung the capture for good.
            Spawn.Completed shot = Spawn.run(CAPTURE_TIMEOUT, argv(out));
            if (shot == null) {
                Diag.error("[capture] " + binary + " did not finish in " + CAPTURE_TIMEOUT
                        + "; no desktop grab (the Robot is skipped on Wayland: it asks the screen-cast portal).");
                return Attempt.FAILED;
            }
            if (shot.ok() && Files.size(out) > 0) {
                BufferedImage image = ImageIO.read(out.toFile());
                if (image != null) {
                    return Attempt.of(image);
                }
            }
            Diag.error("[capture] " + binary + " returned no image (exit " + shot.exitCode() + ").");
            return Attempt.EMPTY;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Attempt.FAILED;
        } catch (Exception e) {
            Diag.error("[capture] " + binary + " capture failed: " + e.getMessage());
            return Attempt.EMPTY;
        } finally {
            if (out != null) {
                try { Files.deleteIfExists(out); } catch (Exception ignored) {}
            }
        }
    }
}
