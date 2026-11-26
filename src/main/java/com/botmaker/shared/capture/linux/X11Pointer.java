// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.WindowPointer;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * Points on a plain X11 desktop: the pointer is grabbed with the crosshair cursor and the keyboard for Esc, on a
 * connection of its own; the first button press names the window under it, resolved to its client window (the one
 * carrying {@code WM_STATE}, inside the window manager's frame). Grabbed, so the click never reaches that window.
 */
public final class X11Pointer implements WindowPointer {

    private static final long POLL_MS = 20;
    private static final long RELEASE_WAIT_MS = 1_000;
    private static final int XK_ESCAPE = 0xff1b;
    // XButtonEvent / XKeyEvent offsets on 64-bit Xlib: type, serial, send_event, display, window, root, subwindow…
    private static final long SUBWINDOW_OFFSET = 48;
    private static final long KEYCODE_OFFSET = 84;
    private static final long BUTTON_OFFSET = 84; // the same field: XButtonEvent.button, XKeyEvent.keycode
    private static final int XEVENT_SIZE = 192;

    private final String displayName;
    private final Supplier<List<GenericWindow>> capturable;

    public X11Pointer(String displayName, Supplier<List<GenericWindow>> capturable) {
        this.displayName = displayName;
        this.capturable = capturable;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Pointed point(Duration timeout) {
        X11ErrorTrap.install();
        X11 x = X11.INSTANCE;
        Pointer display = x.XOpenDisplay(displayName);
        if (display == null) return new Pointed.Unreadable("The X display " + displayName + " couldn't be opened");
        try {
            Pointer root = x.XDefaultRootWindow(display);
            Pointer cursor = x.XCreateFontCursor(display, X11.XC_crosshair);
            int mask = (int) (X11.ButtonPressMask | X11.ButtonReleaseMask);
            if (x.XGrabPointer(display, root, false, mask, X11.GrabModeAsync, X11.GrabModeAsync, null, cursor,
                    X11.CurrentTime) != X11.GrabSuccess) {
                x.XFreeCursor(display, cursor);
                return new Pointed.Unreadable("Another program holds the mouse; try again");
            }
            try {
                if (x.XGrabKeyboard(display, root, false, X11.GrabModeAsync, X11.GrabModeAsync, X11.CurrentTime)
                        != X11.GrabSuccess) {
                    return new Pointed.Unreadable("Another program holds the keyboard; try again");
                }
                Pointer frame = awaitClick(x, display, System.currentTimeMillis() + timeout.toMillis());
                if (frame == null) return new Pointed.Cancelled();
                if (Pointer.nativeValue(frame) == 0) {
                    return new Pointed.Unreadable("That's the desktop — point at the game's window");
                }
                Pointer client = clientOf(display, frame);
                return WindowPointer.resolve(new Clicked(client, X11Utils.getWindowClass(display, client),
                        X11Utils.getWindowTitle(display, client), null), capturable.get(), NOT_LISTED);
            } finally {
                x.XUngrabKeyboard(display, X11.CurrentTime);
                x.XUngrabPointer(display, X11.CurrentTime);
                x.XFreeCursor(display, cursor);
                x.XSync(display, false);
            }
        } finally {
            x.XCloseDisplay(display);
        }
    }

    /**
     * The root's child under the first button press (a zero pointer for the bare root), or null on Esc or past
     * {@code deadline}. The press's release is read too, so it does not reach the window after the ungrab.
     */
    private static Pointer awaitClick(X11 x, Pointer display, long deadline) {
        Memory event = new Memory(XEVENT_SIZE);
        Pointer clicked = null;
        long releaseBy = Long.MAX_VALUE;
        while (true) {
            long now = System.currentTimeMillis();
            if (clicked != null ? now > releaseBy : now > deadline) return clicked;
            if (x.XPending(display) == 0) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return clicked;
                }
                continue;
            }
            x.XNextEvent(display, event);
            int type = event.getInt(0);
            // Buttons 4-7 are the wheel: scrolling while pointing is not a click.
            if (clicked == null && type == X11.ButtonPress && event.getInt(BUTTON_OFFSET) <= 3) {
                Pointer sub = event.getPointer(SUBWINDOW_OFFSET);
                clicked = sub == null ? new Pointer(0) : sub;
                releaseBy = now + RELEASE_WAIT_MS;
            } else if (clicked != null && type == X11.ButtonRelease) {
                return clicked;
            } else if (clicked == null && type == X11.KeyPress) {
                byte keycode = (byte) event.getInt(KEYCODE_OFFSET);
                if (x.XKeycodeToKeysym(display, keycode, 0).intValue() == XK_ESCAPE) return null;
            }
        }
    }

    /** The client window inside {@code frame}: the first, breadth first, that carries {@code WM_STATE}; else the frame. */
    private static Pointer clientOf(Pointer display, Pointer frame) {
        Pointer wmState = X11.INSTANCE.XInternAtom(display, "WM_STATE", false);
        Deque<Pointer> queue = new ArrayDeque<>(List.of(frame));
        while (!queue.isEmpty()) {
            Pointer window = queue.poll();
            if (hasProperty(display, window, wmState)) return window;
            PointerByReference rootReturn = new PointerByReference();
            PointerByReference parentReturn = new PointerByReference();
            PointerByReference childrenReturn = new PointerByReference();
            IntByReference count = new IntByReference();
            if (X11.INSTANCE.XQueryTree(display, window, rootReturn, parentReturn, childrenReturn, count) == 0) {
                continue;
            }
            Pointer children = childrenReturn.getValue();
            if (children == null) continue;
            queue.addAll(List.of(children.getPointerArray(0, count.getValue())));
            X11.INSTANCE.XFree(children);
        }
        return frame;
    }

    private static boolean hasProperty(Pointer display, Pointer window, Pointer property) {
        PointerByReference actualType = new PointerByReference();
        IntByReference format = new IntByReference();
        IntByReference items = new IntByReference();
        IntByReference after = new IntByReference();
        PointerByReference data = new PointerByReference();
        int status = X11.INSTANCE.XGetWindowProperty(display, window, property, 0, 0, false,
                new Pointer(X11.AnyPropertyType), actualType, format, items, after, data);
        if (data.getValue() != null) X11.INSTANCE.XFree(data.getValue());
        return status == X11.Success && actualType.getValue() != null && Pointer.nativeValue(actualType.getValue()) != 0;
    }
}
