package com.botmaker.shared.capture;

import com.botmaker.shared.Diag;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.List;

/**
 * Captures the full virtual desktop via AWT {@link Robot}. Works on Windows, X11, and XWayland.
 * (Under native Wayland this typically returns black — a {@link ToolCapture} program is preferred there.)
 *
 * <p>Always in device pixels ({@link ScreenGeometry}): on a scaled screen a plain Robot grab is downscaled to
 * logical size, so each screen is grabbed at its full resolution and drawn where it sits.
 */
public final class RobotCapture implements CaptureBackend {

    /** None: this backend is AWT inside our own JVM, so there is nothing to find on {@code PATH}. */
    @Override
    public String binaryName() {
        return "";
    }

    @Override
    public BufferedImage captureDesktop() {
        try {
            return capture(new Robot(), ScreenCapture.getVirtualScreenBounds());
        } catch (AWTException e) {
            Diag.error("[capture] Robot desktop capture failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The device rectangle {@code device} of the desktop, at device resolution. With no scaled screen this is
     * one {@link Robot#createScreenCapture}; otherwise one full-resolution grab per screen it covers.
     */
    public static BufferedImage capture(Robot robot, Rectangle device) {
        List<ScreenGeometry.Screen> screens = ScreenCapture.screens();
        if (ScreenGeometry.unscaled(screens)) {
            return robot.createScreenCapture(device);
        }
        BufferedImage out = new BufferedImage(device.width, device.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            for (ScreenGeometry.Screen screen : screens) {
                ScreenGeometry.Piece piece = ScreenGeometry.piece(screen, device);
                if (piece == null) {
                    continue;
                }
                Image full = largest(robot.createMultiResolutionScreenCapture(piece.logical()));
                Rectangle into = piece.device();
                g.drawImage(full, into.x - device.x, into.y - device.y, into.width, into.height, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /** The highest-resolution variant: the device pixels, where the default variant is the logical size. */
    private static Image largest(MultiResolutionImage image) {
        Image best = null;
        for (Image variant : image.getResolutionVariants()) {
            if (best == null || variant.getWidth(null) > best.getWidth(null)) {
                best = variant;
            }
        }
        return best;
    }
}
