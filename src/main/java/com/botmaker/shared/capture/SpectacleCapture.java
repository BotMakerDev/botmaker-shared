package com.botmaker.shared.capture;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Full-desktop capture on KDE Wayland via Spectacle.
 *
 * <p>Runs {@code spectacle -b -n -f -o <tmp>}: {@code -f} captures the entire desktop (all monitors,
 * no on-screen picker), {@code -b} runs in the background and {@code -n} suppresses the notification.
 * The {@code -f} flag is what makes this automatic — without an explicit mode Spectacle falls back to
 * its region/screen picker, which is the "asks which screen to select" prompt we are removing.
 */
public final class SpectacleCapture implements CaptureBackend {

    /** A desktop grab is a fraction of a second; past this, the Robot fallback is the better answer. */
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(15);

    /** True when running under Wayland with the {@code spectacle} binary on PATH. */
    static boolean isAvailable() {
        return System.getenv("WAYLAND_DISPLAY") != null && Executables.onPath("spectacle");
    }

    @Override
    public BufferedImage captureDesktop() {
        Path out = null;
        try {
            out = Files.createTempFile("botcap", ".png");
            // Drained and bounded: Spectacle's merged stream used to be started and never read, so a chatty
            // build (a Wayland warning, a KDE debug build) filled the pipe and hung the capture for good.
            Spawn.Completed shot = Spawn.run(CAPTURE_TIMEOUT, "spectacle", "-b", "-n", "-f", "-o", out.toString());
            if (shot == null) {
                Diag.error("[capture] Spectacle did not finish in " + CAPTURE_TIMEOUT + "; falling back to Robot.");
                return new RobotCapture().captureDesktop();
            }
            if (shot.ok() && Files.size(out) > 0) {
                BufferedImage image = ImageIO.read(out.toFile());
                if (image != null) {
                    return image;
                }
            }
            Diag.error("[capture] Spectacle returned no image (exit " + shot.exitCode() + "); falling back to Robot.");
        } catch (Exception e) {
            Diag.error("[capture] Spectacle capture failed: " + e.getMessage() + "; falling back to Robot.");
        } finally {
            if (out != null) {
                try { Files.deleteIfExists(out); } catch (Exception ignored) {}
            }
        }
        // Fall back to XWayland via Robot rather than returning null.
        return new RobotCapture().captureDesktop();
    }
}
