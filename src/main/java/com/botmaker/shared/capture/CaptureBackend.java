package com.botmaker.shared.capture;

import java.awt.image.BufferedImage;

/**
 * A strategy for capturing the entire desktop (all monitors) as a single image.
 *
 * <p>Implementations are stateless and chosen per call by {@link #select()}. This keeps the door
 * open for additional backends (e.g. an xdg-desktop-portal / PipeWire path) without touching
 * callers.
 */
public sealed interface CaptureBackend permits RobotCapture, ToolCapture {

    /** Captures every monitor, in their relative layout, as one image. Returns null on failure. */
    BufferedImage captureDesktop();

    /**
     * The external program this backend shells out to, or {@code ""} when it needs none (AWT
     * {@link java.awt.Robot} runs in-JVM). Single-sourced here for the same reason
     * {@code NestedSession.Backend.binaryName()} is: the availability probe and the {@code Spawn.run} argv have
     * to name the same executable, and they had drifted apart before by being two literals.
     *
     * <p>The empty string is a working answer rather than a special case —
     * {@link com.botmaker.shared.Executables#onPath} says {@code false} for it, which is exactly right for
     * "is this backend's binary installed?" when there is no binary to install.
     */
    String binaryName();

    /**
     * Picks the best backend for the current environment: under Wayland, the first screenshot program
     * installed ({@link ToolCapture}: Spectacle, grim, gnome-screenshot), because AWT {@link java.awt.Robot}
     * returns black there; everything else, and a Wayland machine with none of them, uses {@link RobotCapture}.
     */
    static CaptureBackend select() {
        return ToolCapture.available().<CaptureBackend>map(tool -> tool).orElseGet(RobotCapture::new);
    }
}
