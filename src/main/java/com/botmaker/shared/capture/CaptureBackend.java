package com.botmaker.shared.capture;

import java.awt.image.BufferedImage;

/**
 * A strategy for capturing the entire desktop (all monitors) as a single image.
 *
 * <p>Implementations are stateless and chosen per call by {@link #select()}. This keeps the door
 * open for additional backends (e.g. an xdg-desktop-portal / PipeWire path) without touching
 * callers.
 */
public sealed interface CaptureBackend permits RobotCapture, SpectacleCapture {

    /** Captures every monitor, in their relative layout, as one image. Returns null on failure. */
    BufferedImage captureDesktop();

    /**
     * Picks the best backend for the current environment: KDE-style Wayland with {@code spectacle}
     * available uses {@link SpectacleCapture} (AWT {@link java.awt.Robot} returns black under
     * Wayland); everything else uses {@link RobotCapture}.
     */
    static CaptureBackend select() {
        if (SpectacleCapture.isAvailable()) {
            return new SpectacleCapture();
        }
        return new RobotCapture();
    }
}
