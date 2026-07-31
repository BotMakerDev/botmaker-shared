package com.botmaker.shared.capture;

import java.awt.*;
import java.awt.image.BufferedImage;


/**
 * Central full-desktop capture facade, delegated to a {@link CaptureBackend} selected for the current
 * platform (Robot on X11/Windows, Spectacle on KDE Wayland). This is the single entry point for
 * whole-desktop capture; the SDK's {@code api.capture.Screen}/{@code Desktop}/{@code Monitor} route
 * through it, and Studio's capture picker can use it directly.
 *
 * <p>It sits beside per-window capture ({@link com.botmaker.shared.capture.windows.WindowCapture} and the
 * Linux controller) rather than in the SDK, because the platform knowledge is the same either way — which
 * is also why shared no longer says full-desktop capture belongs in the consumers.
 */
public class ScreenCapture {

    /**
     * Capture all monitors as a single image
     * This captures the virtual screen bounds that encompasses all monitors
     */
    public static BufferedImage captureDesktop() {
        return CaptureBackend.select().captureDesktop();
    }

    /**
     * Get the virtual screen bounds that encompasses all monitors.
     * Single source of truth for multi-monitor bounds, shared by the capture backends.
     */
    public static Rectangle getVirtualScreenBounds() {
        Rectangle virtualBounds = new Rectangle();

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        for (GraphicsDevice screen : screens) {
            GraphicsConfiguration config = screen.getDefaultConfiguration();
            Rectangle bounds = config.getBounds();
            virtualBounds = virtualBounds.union(bounds);
        }

        return virtualBounds;
    }

    /**
     * Bounds of a single monitor by its 0-based index into {@link GraphicsEnvironment#getScreenDevices()},
     * in absolute virtual-screen coordinates. Falls back to the whole virtual desktop for an out-of-range
     * index, so callers always get a usable rectangle.
     */
    public static Rectangle monitorBounds(int index) {
        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (index >= 0 && index < screens.length) {
            return screens[index].getDefaultConfiguration().getBounds();
        }
        return getVirtualScreenBounds();
    }

    /**
     * Captures a single monitor by cropping the full-desktop grab to {@link #monitorBounds(int)}. Routing
     * through {@link #captureDesktop()} keeps the one Wayland/Robot backend selection (no second capture
     * path); returns {@code null} if the desktop grab failed.
     */
    public static BufferedImage captureMonitor(int index) {
        BufferedImage desktop = captureDesktop();
        if (desktop == null) {
            return null;
        }
        Rectangle b = monitorBounds(index);
        Rectangle v = getVirtualScreenBounds();
        int x = clamp(b.x - v.x, 0, desktop.getWidth() - 1);
        int y = clamp(b.y - v.y, 0, desktop.getHeight() - 1);
        int w = clamp(b.width, 1, desktop.getWidth() - x);
        int h = clamp(b.height, 1, desktop.getHeight() - y);
        return desktop.getSubimage(x, y, w, h);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }
}
