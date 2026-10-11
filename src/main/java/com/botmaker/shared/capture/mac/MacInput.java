package com.botmaker.shared.capture.mac;

import com.sun.jna.Pointer;

import java.awt.geom.Point2D;

/**
 * Builds CoreGraphics events and posts them, either as real input ({@code target == null}: {@code CGEventPost}
 * at the HID tap, which moves the cursor and reaches whatever is on top) or into one app's queue
 * ({@code CGEventPostToPid}, which touches no cursor and needs the app neither in front nor focused).
 *
 * <p>Every coordinate here is in points. Synthetic events carry no modifier state, and a mouse move with a
 * button down is a drag to macOS, not a move; this class keeps both, so a held Shift reaches the next click and
 * a held button turns moves into drags.
 */
final class MacInput {

    /**
     * Where background events go: the app's process, and the window inside it. The window id is written into
     * each mouse event so AppKit delivers it to that window instead of hit-testing the screen, where another
     * app's window may be covering the point.
     */
    record Target(int pid, long windowId) {
    }

    /** The modifier flags of the keys this process is holding down. */
    private long heldFlags;
    /** The NativeController button held down (1 left, 2 middle, 3 right), or 0. */
    private int heldButton;
    /** The fractions of a relative move's delta not sent yet, so small moves add up both ways. */
    private double carryX;
    private double carryY;

    // --- Pure mapping, from NativeController's buttons (1 left, 2 middle, 3 right) ---

    /** CoreGraphics' button number: 0 left, 1 right, 2 centre. */
    static int cgButton(int button) {
        return switch (button) {
            case 3 -> 1;
            case 2 -> 2;
            default -> 0;
        };
    }

    static int buttonEventType(int button, boolean press) {
        return switch (button) {
            case 3 -> press ? CoreGraphics.kCGEventRightMouseDown : CoreGraphics.kCGEventRightMouseUp;
            case 2 -> press ? CoreGraphics.kCGEventOtherMouseDown : CoreGraphics.kCGEventOtherMouseUp;
            default -> press ? CoreGraphics.kCGEventLeftMouseDown : CoreGraphics.kCGEventLeftMouseUp;
        };
    }

    /** A move with {@code heldButton} down is that button's drag; with none, a plain move. */
    static int moveEventType(int heldButton) {
        return switch (heldButton) {
            case 0 -> CoreGraphics.kCGEventMouseMoved;
            case 3 -> CoreGraphics.kCGEventRightMouseDragged;
            case 2 -> CoreGraphics.kCGEventOtherMouseDragged;
            default -> CoreGraphics.kCGEventLeftMouseDragged;
        };
    }

    /** The whole points of a delta to send now, and the fraction carried to the next move. */
    record Split(long whole, double carry) {

        /**
         * Truncated toward zero, so a run of −½ steps turns as far as a run of +½ steps: rounding would send +½
         * as 1 and −½ as 0.
         */
        static Split of(double delta, double carry) {
            double total = delta + carry;
            long whole = (long) total;
            return new Split(whole, total - whole);
        }
    }

    // --- Events ---

    synchronized void moveTo(Point2D at, Target target) {
        post(mouseEvent(moveEventType(heldButton), at, cgButton(heldButton), target), target);
    }

    /** A move that also carries the delta, which a game reading mouse deltas (mouselook) goes by. */
    synchronized void moveBy(Point2D from, double dx, double dy, Target target) {
        Split x = Split.of(dx, carryX);
        Split y = Split.of(dy, carryY);
        carryX = x.carry();
        carryY = y.carry();
        Point2D to = new Point2D.Double(from.getX() + x.whole(), from.getY() + y.whole());
        Pointer event = mouseEvent(moveEventType(heldButton), to, cgButton(heldButton), target);
        if (event == null) {
            return;
        }
        CoreGraphics.INSTANCE.CGEventSetIntegerValueField(event, CoreGraphics.kCGMouseEventDeltaX, x.whole());
        CoreGraphics.INSTANCE.CGEventSetIntegerValueField(event, CoreGraphics.kCGMouseEventDeltaY, y.whole());
        post(event, target);
    }

    synchronized void button(int button, boolean press, Point2D at, Target target) {
        heldButton = press ? button : 0;
        Pointer event = mouseEvent(buttonEventType(button, press), at, cgButton(button), target);
        if (event == null) {
            return;
        }
        // A click count of 1: some apps ignore a press whose count is left at 0.
        CoreGraphics.INSTANCE.CGEventSetIntegerValueField(event, CoreGraphics.kCGMouseEventClickState, 1);
        post(event, target);
    }

    /** {@code amount} lines, positive up or away. */
    synchronized void wheel(int amount, Point2D at, Target target) {
        Pointer event = CoreGraphics.INSTANCE.CGEventCreateScrollWheelEvent2(null,
                CoreGraphics.kCGScrollEventUnitLine, 1, amount, 0, 0);
        if (event == null) {
            return;
        }
        CoreGraphics.INSTANCE.CGEventSetLocation(event, new CoreGraphics.CGPoint(at.getX(), at.getY()));
        post(event, target);
    }

    /**
     * A key press or release. A modifier goes out as the {@code FlagsChanged} event apps watch for it, its flag
     * set on the press and cleared on the release; every later event carries the modifiers still held.
     */
    synchronized void key(int macKeyCode, boolean down, Target target) {
        long flag = MacKeyCodes.flagOf(macKeyCode);
        heldFlags = down ? heldFlags | flag : heldFlags & ~flag;
        Pointer event = CoreGraphics.INSTANCE.CGEventCreateKeyboardEvent(null, (short) macKeyCode, down);
        if (event == null) {
            return;
        }
        if (flag != 0) {
            CoreGraphics.INSTANCE.CGEventSetType(event, CoreGraphics.kCGEventFlagsChanged);
        }
        post(event, target, down ? 0 : flag);
    }

    /** Types {@code text} as characters, whatever the keyboard layout, one code point per key press. */
    synchronized void type(String text, Target target) {
        text.codePoints().forEach(cp -> {
            char[] chars = Character.toChars(cp);
            short[] units = new short[chars.length];
            for (int i = 0; i < chars.length; i++) {
                units[i] = (short) chars[i];
            }
            for (boolean down : new boolean[]{true, false}) {
                Pointer event = CoreGraphics.INSTANCE.CGEventCreateKeyboardEvent(null, (short) 0, down);
                if (event == null) {
                    return;
                }
                CoreGraphics.INSTANCE.CGEventKeyboardSetUnicodeString(event, units.length, units);
                post(event, target);
            }
        });
    }

    /** The real cursor's position in points, or {@code null} if macOS won't say. */
    static Point2D location() {
        Pointer event = CoreGraphics.INSTANCE.CGEventCreate(null);
        if (event == null) {
            return null;
        }
        try {
            CoreGraphics.CGPoint p = CoreGraphics.INSTANCE.CGEventGetLocation(event);
            return new Point2D.Double(p.x, p.y);
        } finally {
            Cf.release(event);
        }
    }

    private static Pointer mouseEvent(int type, Point2D at, int cgButton, Target target) {
        Pointer event = CoreGraphics.INSTANCE.CGEventCreateMouseEvent(null, type,
                new CoreGraphics.CGPoint(at.getX(), at.getY()), cgButton);
        if (event != null && target != null) {
            CoreGraphics.INSTANCE.CGEventSetIntegerValueField(event,
                    CoreGraphics.kCGMouseEventWindowUnderMousePointer, target.windowId());
            CoreGraphics.INSTANCE.CGEventSetIntegerValueField(event,
                    CoreGraphics.kCGMouseEventWindowUnderMousePointerThatCanHandleThisEvent, target.windowId());
        }
        return event;
    }

    private void post(Pointer event, Target target) {
        post(event, target, 0);
    }

    /**
     * Posts and releases {@code event}, adding the held modifiers to the flags it was made with (the user's own
     * held keys included) and taking away {@code released}, the modifier this very event lets go of. A
     * {@code null} event (macOS refused to make it) is dropped.
     */
    private void post(Pointer event, Target target, long released) {
        if (event == null) {
            return;
        }
        try {
            if (!MacPermission.ACCESSIBILITY.check()) {
                return;
            }
            long flags = (CoreGraphics.INSTANCE.CGEventGetFlags(event) | heldFlags) & ~released;
            CoreGraphics.INSTANCE.CGEventSetFlags(event, flags);
            if (target == null) {
                CoreGraphics.INSTANCE.CGEventPost(CoreGraphics.kCGHIDEventTap, event);
            } else {
                CoreGraphics.INSTANCE.CGEventPostToPid(target.pid(), event);
            }
        } finally {
            Cf.release(event);
        }
    }
}
