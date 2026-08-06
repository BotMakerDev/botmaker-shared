package com.botmaker.shared.capture;

import com.botmaker.shared.Diag;
import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * Captures the full virtual desktop via AWT {@link Robot}. Works on Windows, X11, and XWayland.
 * (Under native Wayland this typically returns black — {@link SpectacleCapture} is preferred there.)
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
            Rectangle virtualBounds = ScreenCapture.getVirtualScreenBounds();
            return new Robot().createScreenCapture(virtualBounds);
        } catch (AWTException e) {
            Diag.error("[capture] Robot desktop capture failed: " + e.getMessage());
            return null;
        }
    }
}
