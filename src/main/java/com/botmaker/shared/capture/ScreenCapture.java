package com.botmaker.shared.capture;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;


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
     * The virtual desktop — every monitor's union — in device pixels. Single source of truth for multi-monitor
     * bounds, shared by the capture backends.
     */
    public static Rectangle getVirtualScreenBounds() {
        return ScreenGeometry.deviceUnion(screens());
    }

    /**
     * Bounds of a single monitor by its 0-based index into {@link GraphicsEnvironment#getScreenDevices()},
     * in device pixels of the virtual desktop. Falls back to the whole virtual desktop for an out-of-range
     * index, so callers always get a usable rectangle.
     */
    public static Rectangle monitorBounds(int index) {
        List<ScreenGeometry.Screen> screens = screens();
        if (index >= 0 && index < screens.size()) {
            return screens.get(index).device();
        }
        return ScreenGeometry.deviceUnion(screens);
    }

    /**
     * Every monitor as AWT reports it — logical bounds and the scale to device pixels — in
     * {@link GraphicsEnvironment#getScreenDevices()} order. See {@link ScreenGeometry} for why the scale matters.
     */
    public static List<ScreenGeometry.Screen> screens() {
        List<ScreenGeometry.Screen> screens = new ArrayList<>();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration config = device.getDefaultConfiguration();
            Rectangle b = config.getBounds();
            AffineTransform scale = config.getDefaultTransform();
            screens.add(new ScreenGeometry.Screen(b.x, b.y, b.width, b.height, scale.getScaleX(), scale.getScaleY()));
        }
        return screens;
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
