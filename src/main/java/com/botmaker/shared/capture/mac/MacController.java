package com.botmaker.shared.capture.mac;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.IgnoredClickWatch;
import com.botmaker.shared.capture.NativeController;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The macOS {@link NativeController}. Two input paths, chosen by the bot's "Take over mouse &amp; keyboard"
 * setting ({@link #useReliableInput()}), as on Windows:
 * <ul>
 *   <li><b>Background</b> (the default): every gesture is an event posted into the target app's queue
 *       ({@code CGEventPostToPid}) and addressed to the target window. The user's cursor never moves and the app
 *       need not be in front. Apps that read the HID devices or mouse deltas — most 3D games — ignore it, and
 *       some only take clicks while frontmost; the first clicks are watched for exactly that
 *       ({@link IgnoredClickWatch}).</li>
 *   <li><b>Take over</b>: real input at the HID tap ({@code CGEventPost}), which every app reads. It moves the
 *       cursor and lands on whatever is on top.</li>
 * </ul>
 * Neither isolates the bot from the user: macOS has one cursor and one key window, like Windows. Isolation is a
 * VM's job ({@code docs/refactor/44-windows-isolation.md} for the Windows side of the same problem).
 *
 * <p><b>Coordinates are pixels</b>, as everywhere else in BotMaker: a window's rect is its frame (title bar
 * included) in pixels, the same rectangle its capture covers, so a frame pixel plus the rect's corner is the
 * screen pixel it shows at. {@link MacDisplays} converts to the points CoreGraphics speaks.
 *
 * <p>Both paths need the Accessibility grant and capture needs Screen Recording ({@link MacPermission}); a
 * missing one is said once, by name, and the call does nothing (a capture answers {@code null}).
 *
 * <p>Key codes arrive as X11 keysyms — {@code Key.nativeCode()} answers that on every OS but Windows — and are
 * translated by {@link MacKeyCodes}. Overlay stacking and click-through ({@link #promoteOverlayAboveFullscreen},
 * {@link #makeInputTransparent}) keep their defaults: both need AppKit, which no C API reaches.
 */
public class MacController implements NativeController {

    private static final long OWN_PID = ProcessHandle.current().pid();
    /** How long a take-over click waits for a raised window to come to the front; AX raises asynchronously. */
    private static final long RAISE_WAIT_MS = 500;
    private static final long RAISE_POLL_MS = 25;

    private final MacInput input = new MacInput();
    private final IgnoredClickWatch<MacWindow.Ref> watch = new IgnoredClickWatch<>(
            ref -> MacCapture.capture(ref.id()),
            hint -> Diag.warn("macOS", hint),
            (task, ms) -> CompletableFuture.runAsync(task,
                    CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS)));

    /** Whether input has been escalated to take over the real devices; sticky and process-wide. */
    private volatile boolean reliableInput = false;

    /**
     * Where the background pointer last went, in points, and the window it is over — found once when it moves,
     * so the presses, wheel turns and drag steps that follow don't each re-read the window list.
     */
    private record Posted(Point2D at, MacInput.Target target) {
    }

    private volatile Posted posted;

    // --- Windows ---

    @Override
    public GenericWindow getForegroundWindow() {
        MacDisplays displays = MacDisplays.current();
        return MacWindows.list(false).stream().findFirst().map(w -> toGeneric(w, displays)).orElse(null);
    }

    /** macOS has no child windows in the window list; a window's controls are not windows. */
    @Override
    public List<GenericWindow> getChildWindows(GenericWindow parent) {
        return List.of();
    }

    @Override
    public List<GenericWindow> getAllWindows() {
        return getAllWindows(false);
    }

    /** {@code includeMinimized} also lists windows that are minimized, on another Space or of a hidden app. */
    @Override
    public List<GenericWindow> getAllWindows(boolean includeMinimized) {
        MacDisplays displays = MacDisplays.current();
        return MacWindows.list(includeMinimized).stream().map(w -> toGeneric(w, displays)).toList();
    }

    @Override
    public BufferedImage captureWindow(GenericWindow window) {
        return MacCapture.capture(ref(window).id());
    }

    @Override
    public void focusWindow(GenericWindow window) {
        MacAx.focus(ref(window), nameOf(window));
    }

    @Override
    public void restoreWindow(GenericWindow window) {
        if (window != null) {
            focusWindow(window);
        }
    }

    /** Puts the frame's corner at pixel {@code (x, y)}, the corner the window's rect reports. */
    @Override
    public void moveWindow(GenericWindow window, int x, int y) {
        Point2D at = MacDisplays.current().toPoints(x, y);
        MacAx.move(ref(window), nameOf(window), at.getX(), at.getY());
    }

    /** Makes the frame {@code width × height} pixels, the size the window's rect reports. */
    @Override
    public void resizeWindow(GenericWindow window, int width, int height) {
        MacDisplays displays = MacDisplays.current();
        Rectangle rect = window.getRect();
        MacAx.resize(ref(window), nameOf(window),
                displays.toPoints(width, rect.x, rect.y), displays.toPoints(height, rect.x, rect.y));
    }

    // --- Clicks ---

    @Override
    public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
        MacWindow.Ref ref = ref(window);
        MacDisplays displays = MacDisplays.current();
        // Where the window is now, not where it was when the caller listed it.
        Rectangle rect = MacWindows.byId(ref.id()).map(w -> displays.toPixels(w.bounds())).orElse(window.getRect());
        int x = rect.x + relativeX;
        int y = rect.y + relativeY;
        Point2D at = displays.toPoints(x, y);
        if (reliableInput) {
            // Real input hits whatever is on top, so raise the target first, as Windows and Linux do, and let
            // the raise land before clicking.
            focusWindow(window);
            awaitOnTop(ref, at);
            clickRestoringCursor(x, y, 1);
            return;
        }
        MacInput.Target target = new MacInput.Target(ref.pid(), ref.id());
        watch.around(ref, window.getTitle(), () -> backgroundClick(at, target, 1));
    }

    @Override
    public void click(int xAbs, int yAbs, int button) {
        if (reliableInput) {
            NativeController.super.click(xAbs, yAbs, button);
            return;
        }
        Point2D at = MacDisplays.current().toPoints(xAbs, yAbs);
        windowAt(at).ifPresent(w ->
                watch.around(w.ref(), w.title(), () -> backgroundClick(at, targetOf(w), button)));
    }

    /** In the background there is no cursor to put back: the click is the whole gesture. */
    @Override
    public void clickRestoringCursor(int xAbs, int yAbs, int button) {
        if (reliableInput) {
            NativeController.super.clickRestoringCursor(xAbs, yAbs, button);
        } else {
            click(xAbs, yAbs, button);
        }
    }

    private void backgroundClick(Point2D at, MacInput.Target target, int button) {
        posted = new Posted(at, target);
        input.moveTo(at, target);
        pause(CLICK_SETTLE_MS);
        input.button(button, true, at, target);
        pause(pressHoldMs());
        input.button(button, false, at, target);
    }

    /** Waits, briefly, until {@code ref} is the window on top at {@code at}. */
    private static void awaitOnTop(MacWindow.Ref ref, Point2D at) {
        long deadline = System.currentTimeMillis() + RAISE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (windowAt(at).map(w -> w.id() == ref.id()).orElse(false)) {
                return;
            }
            pause((int) RAISE_POLL_MS);
        }
    }

    /**
     * The real cursor's position once input takes over; {@code null} in the background, where the real cursor
     * is not involved — so a gesture that would put it back afterwards leaves it alone.
     */
    @Override
    public Point cursorPosition() {
        if (!reliableInput) {
            return null;
        }
        Point2D at = MacInput.location();
        return at == null ? null : MacDisplays.current().toPixels(at.getX(), at.getY());
    }

    @Override
    public boolean supportsBackgroundInput() {
        return !reliableInput;
    }

    /** Take over the real mouse and keyboard instead of posting to the app. Idempotent and process-wide. */
    @Override
    public boolean useReliableInput() {
        reliableInput = true;
        return true;
    }

    // --- Keys. With no window they go to whatever has focus, as real input: there is no app to post to. With a
    // window they are posted to its app in the background, or it is focused and typed into once input has taken
    // over. ---

    @Override
    public void keyDown(int nativeKeyCode) {
        key(null, nativeKeyCode, true);
    }

    @Override
    public void keyUp(int nativeKeyCode) {
        key(null, nativeKeyCode, false);
    }

    @Override
    public void typeText(String text) {
        if (text != null) {
            input.type(text, null);
        }
    }

    @Override
    public void keyDown(GenericWindow window, int nativeKeyCode) {
        if (window != null && reliableInput) {
            focusWindow(window);
        }
        key(backgroundTarget(window), nativeKeyCode, true);
    }

    @Override
    public void keyUp(GenericWindow window, int nativeKeyCode) {
        key(backgroundTarget(window), nativeKeyCode, false);
    }

    @Override
    public void typeText(GenericWindow window, String text) {
        if (text == null) {
            return;
        }
        if (window != null && reliableInput) {
            focusWindow(window);
        }
        input.type(text, backgroundTarget(window));
    }

    private void key(MacInput.Target target, int keysym, boolean down) {
        int code = MacKeyCodes.fromKeysym(keysym);
        if (code != MacKeyCodes.NONE) {
            input.key(code, down, target);
        }
    }

    /** Where to post a window's keys, or {@code null} for real input (no window, or input taken over). */
    private MacInput.Target backgroundTarget(GenericWindow window) {
        if (window == null || reliableInput) {
            return null;
        }
        MacWindow.Ref ref = ref(window);
        return new MacInput.Target(ref.pid(), ref.id());
    }

    // --- Pointer ---

    @Override
    public void mouseMove(int xAbs, int yAbs) {
        Point2D at = MacDisplays.current().toPoints(xAbs, yAbs);
        if (reliableInput) {
            input.moveTo(at, null);
            return;
        }
        moveInBackground(at, windowAt(at).map(MacController::targetOf).orElse(null));
    }

    @Override
    public void mouseButton(int button, boolean press) {
        if (reliableInput) {
            Point2D at = MacInput.location();
            if (at != null) {
                input.button(button, press, at, null);
            }
            return;
        }
        Posted p = posted;
        if (p != null && p.target() != null) {
            input.button(button, press, p.at(), p.target());
        }
    }

    @Override
    public void scroll(int amount) {
        if (reliableInput) {
            Point2D at = MacInput.location();
            if (at != null) {
                input.wheel(amount, at, null);
            }
            return;
        }
        Posted p = posted;
        if (p != null && p.target() != null) {
            input.wheel(amount, p.at(), p.target());
        }
    }

    /**
     * A move carrying its delta once input takes over, which mouselook reads. In the background, a move that
     * stays addressed to the window the pointer was over, as a drag's steps must.
     */
    @Override
    public void mouseMoveRelative(int dx, int dy) {
        if (reliableInput) {
            Point2D from = MacInput.location();
            if (from != null) {
                double scale = MacDisplays.current().scaleAtPoint(from.getX(), from.getY());
                input.moveBy(from, dx / scale, dy / scale, null);
            }
            return;
        }
        Posted p = posted;
        if (p == null) {
            return;
        }
        double scale = MacDisplays.current().scaleAtPoint(p.at().getX(), p.at().getY());
        moveInBackground(new Point2D.Double(p.at().getX() + dx / scale, p.at().getY() + dy / scale), p.target());
    }

    private void moveInBackground(Point2D at, MacInput.Target target) {
        posted = new Posted(at, target);
        if (target != null) {
            input.moveTo(at, target);
        }
    }

    // --- Helpers ---

    /** The front-most other app's window at {@code at} (points): this process's own windows are not targets. */
    private static Optional<MacWindow> windowAt(Point2D at) {
        List<MacWindow> others = MacWindows.list(false).stream().filter(w -> w.pid() != OWN_PID).toList();
        return MacWindow.topmostAt(others, at.getX(), at.getY());
    }

    private static MacInput.Target targetOf(MacWindow w) {
        return new MacInput.Target(w.pid(), w.id());
    }

    /** The window list's own name for {@code window}, which AX's fallback match needs; its title if gone. */
    private static String nameOf(GenericWindow window) {
        return MacWindows.byId(ref(window).id()).map(MacWindow::name).orElse(window.getTitle());
    }

    private static GenericWindow toGeneric(MacWindow w, MacDisplays displays) {
        return new GenericWindow(w.ref(), w.title(), displays.toPixels(w.bounds()), w.pid(), w.owner());
    }

    private static MacWindow.Ref ref(GenericWindow window) {
        return (MacWindow.Ref) window.getNativeHandle();
    }

    private static void pause(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
