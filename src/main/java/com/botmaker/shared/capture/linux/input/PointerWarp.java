package com.botmaker.shared.capture.linux.input;

/**
 * How the X server on the other end interprets the coordinates of an absolute pointer warp
 * ({@code XTestFakeMotionEvent}). A closed set rather than a boolean flag, because the two cases are not
 * "normal vs. broken" — each is correct for its server, and a third could appear.
 *
 * <p><b>{@link #ROOT_ABSOLUTE}</b> is the X11 contract and what every real X server, Xvfb and Xephyr do: the
 * coordinates are root-relative, so warping to {@code (x, y)} puts the pointer at root {@code (x, y)}.
 *
 * <p><b>{@link #FOCUS_RELATIVE}</b> is gamescope's embedded Xwayland. It routes injected motion through the
 * focused surface, so the coordinates land <em>window-relative</em>: measured on gamescope 3.16 with
 * {@code -W 1280 -H 720 -w 1280 -h 720 --force-windows-fullscreen}, the focus window sits at root {@code (2,2)}
 * and a warp to root {@code (640, 360)} put the pointer at root {@code (642, 362)} — confirmed not to be a
 * read-back artifact by reading {@code x_root}/{@code y_root} out of the {@code ButtonPress} the client itself
 * received. Every click therefore landed 2px off target. Subtracting the focus window's origin before the warp
 * made all four probed points land exactly, so the correction is derived from the live geometry rather than
 * hardcoded as "+2" — if a future gamescope stops insetting, the correction becomes zero on its own.
 */
public enum PointerWarp {

    /** Coordinates are root-relative — the X11 norm (real servers, Xvfb, Xephyr). */
    ROOT_ABSOLUTE,

    /** Coordinates are interpreted relative to the focused window's origin (gamescope's Xwayland). */
    FOCUS_RELATIVE
}
