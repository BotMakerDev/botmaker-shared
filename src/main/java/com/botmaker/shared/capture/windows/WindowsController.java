package com.botmaker.shared.capture.windows;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The Windows {@link NativeController}. Two input paths, chosen by the bot's "Take over mouse &amp; keyboard"
 * setting ({@link #useReliableInput()}):
 * <ul>
 *   <li><b>Background</b> (the default): every gesture is a message posted to the target window
 *       ({@link PostedInput}) — clicks of every button, moves, drags, the wheel and keys. The user's cursor and
 *       keyboard are never touched and the game need not be in front. Games reading raw input ignore it, and
 *       the first clicks are watched for exactly that ({@link IgnoredClickWatch}).</li>
 *   <li><b>Take over</b>: the real pointer and keyboard through {@code SendInput} with scan codes
 *       ({@link SendInputs}), which those games read. It lands on whatever is on top.</li>
 * </ul>
 *
 * <p><b>A window's rect is its client area</b>, in screen pixels: the area {@link WindowCapture} returns, so a
 * frame pixel plus the rect's corner is the screen pixel it shows at. (It used to be {@code GetWindowRect},
 * title bar and borders included, while the frame was the client area — every click on a windowed game landed
 * a title bar's height too high.) Every coordinate is physical, whatever the screen's scale ({@link WindowsDpi}).
 */
public class WindowsController implements NativeController {

    static {
        WindowsDpi.declareProcessAware();
    }

    private final PostedInput posted = new PostedInput();
    private final IgnoredClickWatch<HWND> watch = new IgnoredClickWatch<>(WindowCapture::capture,
            hint -> Diag.warn("Windows", hint),
            (task, ms) -> CompletableFuture.runAsync(task,
                    CompletableFuture.delayedExecutor(ms, TimeUnit.MILLISECONDS)));

    /** Whether input has been escalated to take over the real devices; sticky and process-wide. */
    private volatile boolean reliableInput = false;

    @Override
    public GenericWindow getForegroundWindow() {
        return toGenericWindow(User32.INSTANCE.GetForegroundWindow());
    }

    @Override
    public List<GenericWindow> getChildWindows(GenericWindow parent) {
        HWND parentHwnd = (HWND) parent.getNativeHandle();
        return WindowFinder.getChildWindows(parentHwnd).stream()
            .map(info -> toGenericWindow(info.getHWnd()))
            .collect(Collectors.toList());
    }

    @Override
    public List<GenericWindow> getAllWindows() {
        return WindowFinder.getAllWindows().stream()
            .map(info -> toGenericWindow(info.getHWnd()))
            .collect(Collectors.toList());
    }

    @Override
    public BufferedImage captureWindow(GenericWindow window) {
        return WindowCapture.capture((HWND) window.getNativeHandle());
    }

    @Override
    public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
        HWND hwnd = (HWND) window.getNativeHandle();
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            POINT pt = new POINT(relativeX, relativeY);
            User32.INSTANCE.ClientToScreen(hwnd, pt);
            if (reliableInput) {
                // Real input hits whatever is topmost, so raise the target first (same rule as the Linux
                // xdotool path), then click with the cursor put back afterwards.
                focusWindow(window);
                clickRestoringCursor(pt.x, pt.y, 1);
                return;
            }
            watch.around(User32.INSTANCE.GetAncestor(hwnd, User32.GA_ROOT), window.getTitle(),
                    () -> posted.click(hwnd, pt.x, pt.y, 1, pressHoldMs()));
        }
    }

    @Override
    public void click(int xAbs, int yAbs, int button) {
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            if (reliableInput) {
                NativeController.super.click(xAbs, yAbs, button);
                return;
            }
            Runnable click = () -> posted.click(null, xAbs, yAbs, button, pressHoldMs());
            if (!watch.watching()) {
                click.run();
                return;
            }
            HWND root = PostedInput.topLevelAt(null, xAbs, yAbs);
            watch.around(root, root == null ? "" : titleOf(root), click);
        }
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

    /**
     * The real pointer's position once input takes over; {@code null} in the background, where the real pointer
     * is not involved — so a gesture that would put it back afterwards leaves it alone.
     */
    @Override
    public Point cursorPosition() {
        if (!reliableInput) {
            return null;
        }
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            POINT pt = new POINT();
            return User32.INSTANCE.GetCursorPos(pt) ? new Point(pt.x, pt.y) : null;
        }
    }

    /** True on the background path, which drives a window without touching the cursor. */
    @Override
    public boolean supportsBackgroundInput() {
        return !reliableInput;
    }

    /**
     * Take over the real mouse and keyboard ({@code SendInput}) instead of posting messages.
     *
     * <p>Posting is cursor-safe, but Wine/Proton and raw-input/DirectInput games never look at their message
     * queue, so every posted gesture is silently dropped. Escalating trades background operation for the input
     * actually landing. Idempotent and process-wide, matching the Linux backend swap.
     */
    @Override
    public boolean useReliableInput() {
        reliableInput = true;
        return true;
    }

    @Override
    public void focusWindow(GenericWindow window) {
        HWND hwnd = (HWND) window.getNativeHandle();
        User32.INSTANCE.ShowWindow(hwnd, User32.SW_RESTORE);
        User32.INSTANCE.SetForegroundWindow(hwnd);
    }

    @Override
    public void restoreWindow(GenericWindow window) {
        if (window == null) return;
        focusWindow(window);
    }

    /** Puts the client area's corner at {@code (x, y)}, the corner the window's rect reports. */
    @Override
    public void moveWindow(GenericWindow window, int x, int y) {
        HWND hwnd = (HWND) window.getNativeHandle();
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            Rectangle outer = windowRect(hwnd);
            Rectangle client = clientRect(hwnd);
            int dx = client == null ? 0 : client.x - outer.x;
            int dy = client == null ? 0 : client.y - outer.y;
            User32.INSTANCE.SetWindowPos(hwnd, null, x - dx, y - dy, 0, 0,
                User32.SWP_NOSIZE | User32.SWP_NOZORDER | User32.SWP_NOACTIVATE);
        }
    }

    /** Makes the client area {@code width × height}, the size the window's rect reports. */
    @Override
    public void resizeWindow(GenericWindow window, int width, int height) {
        HWND hwnd = (HWND) window.getNativeHandle();
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            Rectangle outer = windowRect(hwnd);
            Rectangle client = clientRect(hwnd);
            int extraW = client == null ? 0 : outer.width - client.width;
            int extraH = client == null ? 0 : outer.height - client.height;
            User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, width + extraW, height + extraH,
                User32.SWP_NOMOVE | User32.SWP_NOZORDER | User32.SWP_NOACTIVATE);
        }
    }

    // --- Keys. With no window they go to whatever has focus, through the real keyboard: there is no target to
    // post to. With a window they are posted to it in the background, or it is focused and typed into when input
    // has taken over. ---

    @Override
    public void keyDown(int nativeKeyCode) {
        SendInputs.key(nativeKeyCode, true);
    }

    @Override
    public void keyUp(int nativeKeyCode) {
        SendInputs.key(nativeKeyCode, false);
    }

    @Override
    public void typeText(String text) {
        if (text != null) SendInputs.type(text);
    }

    @Override
    public void keyDown(GenericWindow window, int nativeKeyCode) {
        if (window == null || reliableInput) {
            if (window != null) focusWindow(window);
            keyDown(nativeKeyCode);
            return;
        }
        posted.key((HWND) window.getNativeHandle(), nativeKeyCode, true);
    }

    @Override
    public void keyUp(GenericWindow window, int nativeKeyCode) {
        if (window == null || reliableInput) {
            keyUp(nativeKeyCode);
            return;
        }
        posted.key((HWND) window.getNativeHandle(), nativeKeyCode, false);
    }

    @Override
    public void typeText(GenericWindow window, String text) {
        if (text == null) return;
        if (window == null || reliableInput) {
            if (window != null) focusWindow(window);
            typeText(text);
            return;
        }
        PostedInput.type((HWND) window.getNativeHandle(), text);
    }

    // --- Pointer ---

    @Override
    public void mouseMove(int xAbs, int yAbs) {
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            if (reliableInput) {
                User32.INSTANCE.SetCursorPos(xAbs, yAbs);
            } else {
                posted.move(null, xAbs, yAbs);
            }
        }
    }

    @Override
    public void mouseButton(int button, boolean press) {
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            if (reliableInput) {
                SendInputs.button(button, press);
            } else {
                posted.button(null, button, press);
            }
        }
    }

    @Override
    public void scroll(int amount) {
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            if (reliableInput) {
                SendInputs.wheel(amount);
            } else {
                posted.wheel(null, amount);
            }
        }
    }

    /** A genuine relative motion once input takes over, which mouselook reads; in the background, a move. */
    @Override
    public void mouseMoveRelative(int dx, int dy) {
        if (reliableInput) {
            SendInputs.moveBy(dx, dy);
            return;
        }
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            Point at = posted.position();
            if (at != null) {
                posted.move(null, at.x + dx, at.y + dy);
            }
        }
    }

    // --- Geometry ---

    /** {@code hwnd}'s client area in screen pixels, or {@code null} when Windows can't say. */
    static Rectangle clientRect(HWND hwnd) {
        RECT client = new RECT();
        POINT corner = new POINT(0, 0);
        if (!User32.INSTANCE.GetClientRect(hwnd, client) || !User32.INSTANCE.ClientToScreen(hwnd, corner)) {
            return null;
        }
        return new Rectangle(corner.x, corner.y, client.right - client.left, client.bottom - client.top);
    }

    private static Rectangle windowRect(HWND hwnd) {
        RECT r = new RECT();
        User32.INSTANCE.GetWindowRect(hwnd.getPointer(), r);
        return new Rectangle(r.left, r.top, r.right - r.left, r.bottom - r.top);
    }

    private static String titleOf(HWND hwnd) {
        byte[] text = new byte[512];
        User32.INSTANCE.GetWindowTextA(hwnd.getPointer(), text, 512);
        return Native.toString(text);
    }

    private GenericWindow toGenericWindow(HWND hwnd) {
        if (hwnd == null) return null;
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            Rectangle client = clientRect(hwnd);
            return new GenericWindow(hwnd, titleOf(hwnd), client != null ? client : windowRect(hwnd));
        }
    }
}
