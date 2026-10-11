package com.botmaker.shared.input.mac;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.mac.CoreGraphics;
import com.botmaker.shared.capture.mac.MacDisplays;
import com.botmaker.shared.capture.mac.MacKeyCodes;
import com.botmaker.shared.capture.mac.MacPermission;
import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

import java.awt.Point;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * macOS implementation of {@link InputListener}: a listen-only CoreGraphics event tap on the user's session. It
 * observes, never blocks, so every event still reaches the app it was meant for.
 *
 * <p>A tap delivers on a CoreFoundation run loop, so the listener owns a daemon thread that creates the tap,
 * adds it to that thread's loop and runs the loop in short slices until {@link #close()}. Each slice checks
 * {@code closed}, so a close that lands before the loop starts still ends it; {@code CFRunLoopStop} only makes
 * the end prompt. The thread invalidates and releases the tap and its source on the way out.
 *
 * <p>Events come out as Linux's would: pixel coordinates ({@link MacDisplays}, so a click on a Retina display
 * reads as the pixel a window capture shows), X button numbers, and X keysyms at XRecord's two levels, plain
 * and Shift ({@link #keysymOf}). {@code keycode} is the Mac virtual key. A modifier, which macOS reports as a
 * change of flags, comes out as a press or a release of its own side's key.
 *
 * <p>Needs Input Monitoring for keys: without it macOS still delivers the mouse and silently drops every key, so
 * {@link #start} refuses instead, with the pane that grants it. Secure input (a password field has focus) hides
 * keys from every tap; nothing can see them then.
 */
public final class MacInputListener implements InputListener {

    /** How long one run-loop slice lasts: the longest a close waits if {@code CFRunLoopStop} is missed. */
    private static final double SLICE_SECONDS = 0.25;
    /** Creating a tap is a local call; one that takes longer than this is not coming. */
    private static final long START_TIMEOUT_MS = 2000;

    // Short names for the event types the switches read; constants, so no native library loads for them.
    private static final int LEFT_DOWN = CoreGraphics.kCGEventLeftMouseDown;
    private static final int LEFT_UP = CoreGraphics.kCGEventLeftMouseUp;
    private static final int RIGHT_DOWN = CoreGraphics.kCGEventRightMouseDown;
    private static final int RIGHT_UP = CoreGraphics.kCGEventRightMouseUp;
    private static final int MOVED = CoreGraphics.kCGEventMouseMoved;
    private static final int LEFT_DRAGGED = CoreGraphics.kCGEventLeftMouseDragged;
    private static final int RIGHT_DRAGGED = CoreGraphics.kCGEventRightMouseDragged;
    private static final int FLAGS_CHANGED = CoreGraphics.kCGEventFlagsChanged;
    private static final int OTHER_DOWN = CoreGraphics.kCGEventOtherMouseDown;
    private static final int OTHER_UP = CoreGraphics.kCGEventOtherMouseUp;
    private static final int OTHER_DRAGGED = CoreGraphics.kCGEventOtherMouseDragged;

    private static final long EVENTS = mask(LEFT_DOWN, LEFT_UP, RIGHT_DOWN, RIGHT_UP, MOVED, LEFT_DRAGGED,
            RIGHT_DRAGGED, CoreGraphics.kCGEventKeyDown, CoreGraphics.kCGEventKeyUp, FLAGS_CHANGED,
            CoreGraphics.kCGEventScrollWheel, OTHER_DOWN, OTHER_UP, OTHER_DRAGGED);

    /** {@code kVK_CapsLock}: a toggle, reported once per press as a change of the alpha-shift flag. */
    private static final int CAPS_LOCK = 0x39;
    private static final long FLAG_ALPHA_SHIFT = 0x10000;
    private static final long FLAG_SHIFT = 0x20000;
    /** Control, Option or Command: a character typed with one held is not what X's Shift level would read. */
    private static final long FLAGS_CHORD = 0x40000 | 0x80000 | 0x100000;
    /** The per-side modifier bits a real keyboard sets ({@code NX_DEVICE*KEYMASK}); synthetic events lack them. */
    private static final long DEVICE_MODIFIER_BITS = 0x1 | 0x2 | 0x4 | 0x8 | 0x10 | 0x20 | 0x40 | 0x2000;
    /** Trackpad points that make one wheel click, about what one notch of a mouse wheel scrolls. */
    static final double POINTS_PER_CLICK = 10;
    /** A burst of line scrolling is cut here, so one flick cannot record hundreds of clicks. */
    private static final int MAX_CLICKS = 10;

    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private Thread thread;
    private volatile boolean closed;
    /** The thread's run loop, once it has one; {@code CFRunLoopStop} may be called on it from any thread. */
    private volatile Pointer loop;
    private volatile Pointer tap;
    // Strong reference so the JNA callback isn't garbage-collected while native code holds it.
    private CoreGraphics.TapCallback callback;
    /** The part of a trackpad scroll too small for a click yet; tap thread only. */
    private double wheelCarry;

    @Override
    public synchronized void start(Consumer<InputEvent> sink) {
        if (thread != null) throw new IllegalStateException("already started");
        if (!MacPermission.INPUT_MONITORING.check()) {
            throw new IllegalStateException(MacPermission.INPUT_MONITORING.hint());
        }
        callback = (proxy, type, event, userInfo) -> {
            if (type == CoreGraphics.kCGEventTapDisabledByTimeout
                    || type == CoreGraphics.kCGEventTapDisabledByUserInput) {
                Pointer t = tap;
                if (t != null) CoreGraphics.INSTANCE.CGEventTapEnable(t, true);
                return event;
            }
            try {
                decode(type, event, sink);
            } catch (RuntimeException | Error e) {
                // Never let a throw unwind into CoreGraphics; say once why events go missing.
                if (failureLogged.compareAndSet(false, true)) {
                    Diag.warn("macOS", "The input listener dropped an event: " + e);
                }
            }
            return event;
        };
        CompletableFuture<Void> started = new CompletableFuture<>();
        thread = new Thread(() -> runLoop(started), "botmaker-input-recorder");
        thread.setDaemon(true);
        thread.start();
        try {
            started.get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            close();
            throw new IllegalStateException("The macOS event tap did not start.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Interrupted while starting the macOS event tap.");
        }
    }

    private void runLoop(CompletableFuture<Void> started) {
        CoreGraphics cg = CoreGraphics.INSTANCE;
        RunLoops cf = RunLoops.INSTANCE;
        Pointer port = cg.CGEventTapCreate(CoreGraphics.kCGSessionEventTap, CoreGraphics.kCGHeadInsertEventTap,
                CoreGraphics.kCGEventTapOptionListenOnly, EVENTS, callback, null);
        if (port == null) {
            started.completeExceptionally(new IllegalStateException(
                    "macOS refused the event tap. " + MacPermission.INPUT_MONITORING.hint()));
            return;
        }
        Pointer source = cf.CFMachPortCreateRunLoopSource(null, port, 0);
        if (source == null) {
            cf.CFMachPortInvalidate(port);
            cf.CFRelease(port);
            started.completeExceptionally(new IllegalStateException("macOS could not schedule the event tap."));
            return;
        }
        try {
            Pointer mode = RunLoops.defaultMode();
            Pointer current = cf.CFRunLoopGetCurrent();
            cf.CFRunLoopAddSource(current, source, mode);
            cg.CGEventTapEnable(port, true);
            tap = port;
            loop = current;
            started.complete(null);
            while (!closed) {
                cf.CFRunLoopRunInMode(mode, SLICE_SECONDS, (byte) 0);
            }
        } finally {
            tap = null;
            loop = null;
            cf.CFMachPortInvalidate(port);
            cf.CFRelease(source);
            cf.CFRelease(port);
        }
    }

    /** Turns one tapped event into the {@link InputEvent}s it stands for, if any. Tap thread only. */
    private void decode(int type, Pointer event, Consumer<InputEvent> sink) {
        CoreGraphics cg = CoreGraphics.INSTANCE;
        long now = System.currentTimeMillis();
        switch (type) {
            case CoreGraphics.kCGEventKeyDown, CoreGraphics.kCGEventKeyUp -> {
                int keyCode = (int) cg.CGEventGetIntegerValueField(event, CoreGraphics.kCGKeyboardEventKeycode);
                long keysym = keysymOf(keyCode, cg.CGEventGetFlags(event), () -> typed(cg, event));
                sink.accept(type == CoreGraphics.kCGEventKeyDown
                        ? new InputEvent.KeyPress(keyCode, keysym, now)
                        : new InputEvent.KeyRelease(keyCode, keysym, now));
            }
            case FLAGS_CHANGED -> {
                int keyCode = (int) cg.CGEventGetIntegerValueField(event, CoreGraphics.kCGKeyboardEventKeycode);
                long keysym = MacKeyCodes.toKeysym(keyCode);
                if (keysym == 0) return; // fn, which X has no key for
                if (keyCode == CAPS_LOCK) {
                    sink.accept(new InputEvent.KeyPress(keyCode, keysym, now));
                    sink.accept(new InputEvent.KeyRelease(keyCode, keysym, now));
                } else if (modifierDown(keyCode, cg.CGEventGetFlags(event))) {
                    sink.accept(new InputEvent.KeyPress(keyCode, keysym, now));
                } else {
                    sink.accept(new InputEvent.KeyRelease(keyCode, keysym, now));
                }
            }
            case CoreGraphics.kCGEventScrollWheel -> {
                if (cg.CGEventGetIntegerValueField(event, CoreGraphics.kCGScrollWheelEventMomentumPhase) != 0) {
                    return; // the coast after a swipe: the user's gesture already counted
                }
                boolean continuous =
                        cg.CGEventGetIntegerValueField(event, CoreGraphics.kCGScrollWheelEventIsContinuous) != 0;
                Wheel wheel = continuous
                        ? Wheel.of(cg.CGEventGetIntegerValueField(event,
                                CoreGraphics.kCGScrollWheelEventPointDeltaAxis1), wheelCarry)
                        : Wheel.lines(cg.CGEventGetIntegerValueField(event,
                                CoreGraphics.kCGScrollWheelEventDeltaAxis1));
                wheelCarry = wheel.carry();
                if (wheel.clicks() == 0) return;
                int button = wheel.clicks() > 0 ? 4 : 5;
                Point at = pixels(cg, event);
                for (int i = 0; i < Math.abs(wheel.clicks()); i++) {
                    sink.accept(new InputEvent.ButtonPress(button, at.x, at.y, now));
                    sink.accept(new InputEvent.ButtonRelease(button, at.x, at.y, now));
                }
            }
            case MOVED, LEFT_DRAGGED, RIGHT_DRAGGED, OTHER_DRAGGED -> {
                Point at = pixels(cg, event);
                sink.accept(new InputEvent.Motion(at.x, at.y, now));
            }
            default -> {
                int button = xButton(type, (int) cg.CGEventGetIntegerValueField(event,
                        CoreGraphics.kCGMouseEventButtonNumber));
                if (button == 0) return;
                Point at = pixels(cg, event);
                sink.accept(isPress(type)
                        ? new InputEvent.ButtonPress(button, at.x, at.y, now)
                        : new InputEvent.ButtonRelease(button, at.x, at.y, now));
            }
        }
    }

    private static Point pixels(CoreGraphics cg, Pointer event) {
        CoreGraphics.CGPoint p = cg.CGEventGetLocation(event);
        return MacDisplays.current().toPixels(p.x, p.y);
    }

    /** The characters a key event typed, as macOS's keyboard layout resolved them; empty if none. */
    private static String typed(CoreGraphics cg, Pointer event) {
        short[] units = new short[4];
        LongByReference length = new LongByReference();
        cg.CGEventKeyboardGetUnicodeString(event, units.length, length, units);
        int n = (int) Math.min(Math.max(length.getValue(), 0), units.length);
        char[] chars = new char[n];
        for (int i = 0; i < n; i++) {
            chars[i] = (char) units[i];
        }
        return new String(chars);
    }

    // --- Pure mapping ---

    /**
     * The keysym for Mac key {@code keyCode} under {@code flags}, at XRecord's two levels: plain and Shift.
     * <ul>
     *   <li>A key with a function keysym (F-keys, arrows, Return, modifiers) is that keysym.</li>
     *   <li>With Control, Option or Command held, the key's own keysym, a letter uppercased by Shift: what
     *       Option+A types ({@code å}) is a third level X never reads.</li>
     *   <li>Otherwise the one printable character the layout typed, so a layout's keys read as on Linux; a
     *       letter's case follows Shift alone, as X ignores Caps Lock. Latin-1 is its code point, the rest
     *       {@code 0x01000000 | code point}, as X spells Unicode.</li>
     *   <li>Failing that (no character, or the private-use ones macOS gives function keys), the key's own
     *       keysym; 0 when it has none.</li>
     * </ul>
     * {@code typed} is asked only in the third case, since it is a native call.
     */
    static long keysymOf(int keyCode, long flags, Supplier<String> typed) {
        long own = MacKeyCodes.toKeysym(keyCode);
        if (own >= 0xFF00) return own;
        boolean shift = (flags & FLAG_SHIFT) != 0;
        if ((flags & FLAGS_CHORD) != 0) {
            return shift && own >= 'a' && own <= 'z' ? own - 'a' + 'A' : own;
        }
        String chars = typed.get();
        if (chars.codePointCount(0, chars.length()) == 1) {
            int cp = chars.codePointAt(0);
            boolean printable = cp >= 0x20 && cp != 0x7F && !(cp >= 0xF700 && cp <= 0xF8FF);
            if (printable) {
                if ((flags & FLAG_ALPHA_SHIFT) != 0 && Character.isLetter(cp)) {
                    cp = shift ? Character.toUpperCase(cp) : Character.toLowerCase(cp);
                }
                return cp < 0x100 ? cp : 0x01000000L | cp;
            }
        }
        return own;
    }

    /**
     * Whether a modifier's flag change is its press. Read off its own side's device bit, since left and right
     * share the plain flag (Shift stays set while the other Shift is held); an event with no device bits at all
     * (a synthetic one) falls back to the plain flag.
     */
    static boolean modifierDown(int keyCode, long flags) {
        long flag = MacKeyCodes.flagOf(keyCode);
        if (flag == 0) return false;
        long side = deviceBit(keyCode);
        return (flags & DEVICE_MODIFIER_BITS) != 0 ? (flags & side) != 0 : (flags & flag) != 0;
    }

    /** The {@code NX_DEVICE*KEYMASK} bit of a modifier key, or 0. */
    private static long deviceBit(int keyCode) {
        return switch (keyCode) {
            case 0x3B -> 0x1;    // left Control
            case 0x38 -> 0x2;    // left Shift
            case 0x3C -> 0x4;    // right Shift
            case 0x37 -> 0x8;    // left Command
            case 0x36 -> 0x10;   // right Command
            case 0x3A -> 0x20;   // left Option
            case 0x3D -> 0x40;   // right Option
            case 0x3E -> 0x2000; // right Control
            default -> 0;
        };
    }

    /**
     * Wheel clicks as X reports them, positive up (button 4), negative down (button 5), and the trackpad points
     * not yet worth a click. The sign is macOS's after the natural-scrolling setting, as the app sees it.
     */
    record Wheel(int clicks, double carry) {

        /** A mouse wheel's line count, cut at {@link #MAX_CLICKS}. */
        static Wheel lines(long deltaAxis1) {
            return new Wheel((int) Math.max(-MAX_CLICKS, Math.min(MAX_CLICKS, deltaAxis1)), 0);
        }

        /** A trackpad step in points, added to what was carried: one click per {@link #POINTS_PER_CLICK}. */
        static Wheel of(double points, double carry) {
            double total = points + carry;
            int clicks = (int) Math.max(-MAX_CLICKS, Math.min(MAX_CLICKS, (long) (total / POINTS_PER_CLICK)));
            return new Wheel(clicks, total - clicks * POINTS_PER_CLICK);
        }
    }

    /**
     * The X button of a press or release: 1 left, 3 right, 2 middle, 8 and 9 the back and forward side buttons
     * (CoreGraphics' 3 and 4); 0 for a type that is no button or a button X has no number for.
     */
    static int xButton(int type, int cgButtonNumber) {
        return switch (type) {
            case LEFT_DOWN, LEFT_UP -> 1;
            case RIGHT_DOWN, RIGHT_UP -> 3;
            case OTHER_DOWN, OTHER_UP -> switch (cgButtonNumber) {
                case 2 -> 2;
                case 3 -> 8;
                case 4 -> 9;
                default -> 0;
            };
            default -> 0;
        };
    }

    static boolean isPress(int type) {
        return type == LEFT_DOWN || type == RIGHT_DOWN || type == OTHER_DOWN;
    }

    private static long mask(int... types) {
        long m = 0;
        for (int t : types) {
            m |= 1L << t;
        }
        return m;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Pointer l = loop;
        if (l != null) RunLoops.INSTANCE.CFRunLoopStop(l);
    }
}
