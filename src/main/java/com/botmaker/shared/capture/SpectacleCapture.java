package com.botmaker.shared.capture;

import com.botmaker.shared.Diag;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Full-desktop capture on KDE Wayland via Spectacle.
 *
 * <p>Runs {@code spectacle -b -n -f -o <tmp>}: {@code -f} captures the entire desktop (all monitors,
 * no on-screen picker), {@code -b} runs in the background and {@code -n} suppresses the notification.
 * The {@code -f} flag is what makes this automatic — without an explicit mode Spectacle falls back to
 * its region/screen picker, which is the "asks which screen to select" prompt we are removing.
 */
public final class SpectacleCapture implements CaptureBackend {

    /** True when running under Wayland with the {@code spectacle} binary on PATH. */
    static boolean isAvailable() {
        return System.getenv("WAYLAND_DISPLAY") != null && isOnPath("spectacle");
    }

    @Override
    public BufferedImage captureDesktop() {
        Path out = null;
        try {
            out = Files.createTempFile("botcap", ".png");
            Process p = new ProcessBuilder("spectacle", "-b", "-n", "-f", "-o", out.toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = p.waitFor();
            if (exit == 0 && Files.size(out) > 0) {
                BufferedImage image = ImageIO.read(out.toFile());
                if (image != null) {
                    return image;
                }
            }
            Diag.error("[capture] Spectacle returned no image (exit " + exit + "); falling back to Robot.");
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

    private static boolean isOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            File candidate = new File(dir, executable);
            if (candidate.isFile() && candidate.canExecute()) {
                return true;
            }
        }
        return false;
    }
}
