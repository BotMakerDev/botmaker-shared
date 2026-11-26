// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import com.botmaker.shared.capture.linux.KWinPointer;
import com.botmaker.shared.capture.linux.X11Pointer;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The user points at a window and BotMaker learns which one it is (spec
 * {@code 2026-11-26-point-at-window-design.md}): the system is asked which window was clicked, so the stacking is
 * the system's, native Wayland windows included. KDE asks KWin ({@link KWinPointer}), X11 grabs the pointer
 * ({@link X11Pointer}), Windows hooks the mouse ({@code windows.WindowsPointer}), macOS taps its events
 * ({@code mac.MacPointer}); on any other Linux desktop {@link #available()} is false.
 *
 * <p>The clicked window is resolved to one of {@link NativeController#getAllWindows()}, the windows BotMaker can
 * capture, so a pick is always something a run can read. The click never reaches the window clicked.
 *
 * <p>Not {@code sealed}: shared is not a named module, so the backends could not live in their platform packages.
 */
public interface WindowPointer {

    /**
     * False on a Linux desktop no pointer serves (GNOME Wayland, a Wayland session without KWin, no display); true
     * on Windows and macOS, where a missing permission is the pick's {@link Pointed.Unreadable} instead.
     */
    boolean available();

    /**
     * Blocks until the user clicks a window or cancels: Esc, or {@code timeout} passing, is {@link Pointed.Cancelled}.
     * Never on the FX thread.
     */
    Pointed point(Duration timeout);

    /** What pointing gave. */
    sealed interface Pointed {
        /** The clicked window, one BotMaker can capture. */
        record Window(GenericWindow window) implements Pointed {
            public Window {
                Objects.requireNonNull(window, "window");
            }
        }

        /** The user pressed Esc, or the time ran out. */
        record Cancelled() implements Pointed {
        }

        /** A window was clicked that BotMaker cannot read, and why, said to the user. */
        record Unreadable(String why) implements Pointed {
        }
    }

    /**
     * What the system said of the clicked window: its native handle when the system shares one with
     * {@link GenericWindow#getNativeHandle()} (X11), else null; its class, caption and geometry, each null when
     * not said.
     */
    record Clicked(Object handle, String windowClass, String caption, Rectangle rect) {
    }

    /** Said of a window KWin knows and BotMaker cannot read. */
    String NATIVE_WAYLAND = "BotMaker can't read this window — a native Wayland one";

    /** Said of a window the X server knows and BotMaker does not list. */
    String NOT_LISTED = "BotMaker can't read this window — it has no title or is not a normal window";

    /**
     * The capturable window the user clicked: the same handle; else among those of the clicked class (any case),
     * the one of its caption, then the one closest to its geometry. A clicked window of no class matches only a
     * window of no class and the same caption. None matching is {@code Unreadable(whyNone)}.
     */
    static Pointed resolve(Clicked clicked, List<GenericWindow> capturable, String whyNone) {
        if (clicked.handle() != null) {
            for (GenericWindow window : capturable) {
                if (clicked.handle().equals(window.getNativeHandle())) return new Pointed.Window(window);
            }
        }
        List<GenericWindow> candidates = new ArrayList<>();
        for (GenericWindow window : capturable) {
            boolean match = clicked.windowClass() == null
                    ? window.getWindowClass() == null && clicked.caption() != null
                            && clicked.caption().equals(window.getTitle())
                    : clicked.windowClass().equalsIgnoreCase(window.getWindowClass());
            if (match) candidates.add(window);
        }
        if (candidates.isEmpty()) return new Pointed.Unreadable(whyNone);
        if (candidates.size() > 1 && clicked.caption() != null) {
            List<GenericWindow> named = candidates.stream()
                    .filter(window -> clicked.caption().equals(window.getTitle()))
                    .toList();
            if (!named.isEmpty()) candidates = named;
        }
        if (candidates.size() > 1 && clicked.rect() != null) {
            return new Pointed.Window(candidates.stream()
                    .min(Comparator.comparingLong(window -> distance(clicked.rect(), window.getRect())))
                    .orElseThrow());
        }
        return new Pointed.Window(candidates.getFirst());
    }

    private static long distance(Rectangle a, Rectangle b) {
        if (b == null) return Long.MAX_VALUE;
        return (long) Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.width - b.width)
                + Math.abs(a.height - b.height);
    }

    /**
     * This desktop's pointer, chosen once. The first call may ask the session bus (up to 3 s): never on the FX
     * thread.
     */
    static WindowPointer current() {
        return WindowPointers.CURRENT;
    }

    /** No pointer on this desktop. */
    WindowPointer UNAVAILABLE = new WindowPointer() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Pointed point(Duration timeout) {
            return new Pointed.Unreadable("Pointing at a window isn't available on this desktop");
        }
    };
}
