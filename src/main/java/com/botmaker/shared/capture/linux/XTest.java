package com.botmaker.shared.capture.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * JNA bindings for XTest extension (libXtst)
 * Used for simulating mouse clicks and keyboard events
 */
public interface XTest extends Library {
    XTest INSTANCE = Native.load("Xtst", XTest.class);

    // Mouse button constants
    int Button1 = 1; // Left button
    int Button2 = 2; // Middle button
    int Button3 = 3; // Right button
    int Button4 = 4; // Scroll up
    int Button5 = 5; // Scroll down

    /**
     * Simulates a button press or release event
     *
     * @param display X11 display
     * @param button Button number (1=left, 2=middle, 3=right)
     * @param isPress true for press, false for release
     * @param delay Delay in milliseconds before the event
     * @return Status code
     */
    int XTestFakeButtonEvent(Pointer display, int button, boolean isPress, long delay);

    /**
     * Simulates mouse motion to absolute screen coordinates
     *
     * @param display X11 display
     * @param screen Screen number (-1 for current)
     * @param x Absolute X coordinate
     * @param y Absolute Y coordinate
     * @param delay Delay in milliseconds before the event
     * @return Status code
     */
    int XTestFakeMotionEvent(Pointer display, int screen, int x, int y, long delay);

    /**
     * Simulates a key press or release event
     *
     * @param display X11 display
     * @param keycode Key code
     * @param isPress true for press, false for release
     * @param delay Delay in milliseconds before the event
     * @return Status code
     */
    int XTestFakeKeyEvent(Pointer display, int keycode, boolean isPress, long delay);

    /**
     * Simulates relative mouse motion
     *
     * @param display X11 display
     * @param screen Screen number (-1 for current)
     * @param x Relative X movement
     * @param y Relative Y movement
     * @param delay Delay in milliseconds before the event
     * @return Status code
     */
    int XTestFakeRelativeMotionEvent(Pointer display, int screen, int x, int y, long delay);
}
