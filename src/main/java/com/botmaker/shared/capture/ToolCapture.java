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

    /** A desktop grab is a fraction of a second; past this, the Robot fallback is the better answer. */
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(15);

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

    @Override
    public BufferedImage captureDesktop() {
        Path out = null;
        try {
            out = Files.createTempFile("botcap", ".png");
            // Drained and bounded: Spectacle's merged stream used to be started and never read, so a chatty
            // build (a Wayland warning, a KDE debug build) filled the pipe and hung the capture for good.
            Spawn.Completed shot = Spawn.run(CAPTURE_TIMEOUT, argv(out));
            if (shot == null) {
                Diag.error("[capture] " + binary + " did not finish in " + CAPTURE_TIMEOUT + "; falling back to Robot.");
                return new RobotCapture().captureDesktop();
            }
            if (shot.ok() && Files.size(out) > 0) {
                BufferedImage image = ImageIO.read(out.toFile());
                if (image != null) {
                    return image;
                }
            }
            Diag.error("[capture] " + binary + " returned no image (exit " + shot.exitCode() + "); falling back to Robot.");
        } catch (Exception e) {
            Diag.error("[capture] " + binary + " capture failed: " + e.getMessage() + "; falling back to Robot.");
        } finally {
            if (out != null) {
                try { Files.deleteIfExists(out); } catch (Exception ignored) {}
            }
        }
        // Fall back to XWayland via Robot rather than returning null.
        return new RobotCapture().captureDesktop();
    }
}
