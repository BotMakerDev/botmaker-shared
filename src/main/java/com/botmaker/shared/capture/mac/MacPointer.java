// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture.mac;

import com.botmaker.shared.capture.PointClick;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.WindowPointer;
import com.botmaker.shared.input.mac.RunLoops;
import com.sun.jna.Pointer;

import java.awt.geom.Point2D;
import java.lang.ref.Reference;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Points on macOS: an active event tap on the session, run on the calling thread's run loop until the pick is
 * over; the first left press is swallowed, and the front-most ordinary on-screen window whose bounds hold its point
 * is the pick ({@code CGWindowListCopyWindowInfo} lists front to back, in the same global points as the event).
 * An active tap needs Accessibility, so the pick is refused with its reason without it. macOS gives no other app's
 * cursor to set, so the cursor stays the arrow. Not tried live yet.
 */
public final class MacPointer implements WindowPointer {

    private static final double SLICE_SECONDS = 0.05;
    /** {@code kVK_Escape}. */
    private static final int ESCAPE = 0x35;
    private static final long EVENTS = 1L << CoreGraphics.kCGEventLeftMouseDown
            | 1L << CoreGraphics.kCGEventLeftMouseUp | 1L << CoreGraphics.kCGEventKeyDown;

    private final Supplier<List<GenericWindow>> capturable;

    public MacPointer(Supplier<List<GenericWindow>> capturable) {
        this.capturable = capturable;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Pointed point(Duration timeout) {
        if (!MacPermission.ACCESSIBILITY.check()) return new Pointed.Unreadable(MacPermission.ACCESSIBILITY.hint());
        CoreGraphics cg = CoreGraphics.INSTANCE;
        RunLoops cf = RunLoops.INSTANCE;
        PointClick watch = new PointClick(System.currentTimeMillis() + timeout.toMillis());
        Pointer[] tap = new Pointer[1];
        CoreGraphics.TapCallback callback = (proxy, type, event, userInfo) -> {
            if (type == CoreGraphics.kCGEventTapDisabledByTimeout
                    || type == CoreGraphics.kCGEventTapDisabledByUserInput) {
                if (tap[0] != null) cg.CGEventTapEnable(tap[0], true);
                return event;
            }
            try {
                return swallow(cg, watch, type, event) ? null : event;
            } catch (RuntimeException | Error e) {
                return event; // never unwind into CoreGraphics
            }
        };
        tap[0] = cg.CGEventTapCreate(CoreGraphics.kCGSessionEventTap, CoreGraphics.kCGHeadInsertEventTap,
                CoreGraphics.kCGEventTapOptionDefault, EVENTS, callback, null);
        if (tap[0] == null) {
            return new Pointed.Unreadable("macOS refused the event tap. " + MacPermission.ACCESSIBILITY.hint());
        }
        Pointer source = cf.CFMachPortCreateRunLoopSource(null, tap[0], 0);
        try {
            if (source == null) return new Pointed.Unreadable("macOS could not schedule the event tap");
            Pointer mode = RunLoops.defaultMode();
            cf.CFRunLoopAddSource(cf.CFRunLoopGetCurrent(), source, mode);
            cg.CGEventTapEnable(tap[0], true);
            while (!watch.over(System.currentTimeMillis())) {
                if (Thread.currentThread().isInterrupted()) return new Pointed.Cancelled();
                cf.CFRunLoopRunInMode(mode, SLICE_SECONDS, (byte) 0);
            }
        } finally {
            cf.CFMachPortInvalidate(tap[0]);
            if (source != null) cf.CFRelease(source);
            cf.CFRelease(tap[0]);
            // JNA holds a callback weakly: kept reachable until the tap is gone, or a GC frees its trampoline.
            Reference.reachabilityFence(callback);
        }
        Optional<Point2D> at = watch.pressedAt();
        if (at.isEmpty()) return new Pointed.Cancelled();
        return pointedAt(MacWindows.onScreenAll(), at.get().getX(), at.get().getY())
                .map(w -> WindowPointer.resolve(new Clicked(w.ref(), w.owner(), w.title(), null), capturable.get(),
                        NOT_LISTED))
                .orElse(new Pointed.Unreadable("That's the desktop — point at the game's window"));
    }

    private static boolean swallow(CoreGraphics cg, PointClick watch, int type, Pointer event) {
        return switch (type) {
            case CoreGraphics.kCGEventLeftMouseDown -> {
                CoreGraphics.CGPoint p = cg.CGEventGetLocation(event);
                yield watch.press(p.x, p.y, System.currentTimeMillis());
            }
            case CoreGraphics.kCGEventLeftMouseUp -> watch.release();
            case CoreGraphics.kCGEventKeyDown ->
                    cg.CGEventGetIntegerValueField(event, CoreGraphics.kCGKeyboardEventKeycode) == ESCAPE
                            && watch.escape();
            default -> false;
        };
    }

    /**
     * The window a click at the point reached, in global points: the front-most visible one of a front-to-back list
     * whose bounds hold it (invisible and 1×1 helpers skipped), when it is an ordinary window; empty when it is the
     * menu bar, the Dock or another panel above the windows, so a click there picks nothing beneath.
     */
    static Optional<MacWindow> pointedAt(List<MacWindow> frontToBack, double x, double y) {
        List<MacWindow> visible = frontToBack.stream()
                .filter(w -> w.alpha() > 0 && w.bounds().getWidth() > 1 && w.bounds().getHeight() > 1).toList();
        return MacWindow.topmostAt(visible, x, y).filter(MacWindow::isOrdinary);
    }
}
