package com.botmaker.shared.capture.windows;

import com.botmaker.shared.capture.windows.WindowMessages.ScanCode;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;

import java.awt.Point;

/**
 * The background path: every gesture posted to the target window's message queue, so the user's own cursor and
 * keyboard are never touched and the window need not be in front.
 *
 * <p>Posted input has no cursor, so this class keeps one: the last position a move or a click went to, and the
 * buttons held there. A press or release goes where the pointer last was, and every message reports the held
 * buttons in its {@code MK_*} flags — which is what makes a drag a drag rather than a click followed by moves.
 *
 * <p><b>Which window.</b> A point is posted to the deepest visible child under it, in that child's client
 * coordinates, the window Windows itself would deliver a real click to. The starting window matters when the
 * target is covered: {@code WindowFromPoint} answers whatever is on top, which is the window covering the game.
 * So a caller that knows its target ({@code postLeftClick}) passes it, and the search starts there. A gesture at
 * a bare screen point has no target and goes to the topmost window: guessing one (the window last captured was
 * tried) sent clicks to whatever a picker had just made a thumbnail of.
 *
 * <p>Games that read raw input or DirectInput ignore all of this; the take-over path ({@link SendInputs}) is for
 * them. {@code IgnoredClickWatch} notices when that is the case.
 */
final class PostedInput {

    private int x;
    private int y;
    private boolean placed;
    /** The {@code MK_*} flags of the buttons held down. */
    private int held;

    /** Whether a posted Alt is down: what turns every key message into its {@code WM_SYS*} form. */
    private boolean altHeld;

    /**
     * Where the pointer is: where the last gesture went, and before the first one where the real cursor is, which
     * is the only position a caller pressing, scrolling or moving by a delta without moving first can have meant.
     * {@code null} when not even the real cursor can be read.
     */
    synchronized Point position() {
        if (!placed) {
            POINT cursor = new POINT();
            if (!User32.INSTANCE.GetCursorPos(cursor)) {
                return null;
            }
            place(cursor.x, cursor.y);
        }
        return new Point(x, y);
    }

    /** Move the pointer to screen {@code (sx, sy)}, posting the move to the window under it. */
    synchronized void move(HWND target, int sx, int sy) {
        place(sx, sy);
        Hit hit = hit(target, sx, sy);
        if (hit != null) {
            post(hit.window(), User32.WM_MOUSEMOVE, held, WindowMessages.pointLParam(hit.x(), hit.y()));
        }
    }

    /** Press or release {@code button} where the pointer is ({@link #position()}). */
    synchronized void button(HWND target, int button, boolean press) {
        if (position() == null) {
            return;
        }
        int flag = WindowMessages.buttonFlag(button);
        held = press ? held | flag : held & ~flag;
        Hit hit = hit(target, x, y);
        if (hit != null) {
            post(hit.window(), WindowMessages.buttonMessage(button, press), WindowMessages.buttonWParam(button, held),
                    WindowMessages.pointLParam(hit.x(), hit.y()));
        }
    }

    /** Move, press, hold {@code holdMs}, release — one click, all to the same window. */
    synchronized void click(HWND target, int sx, int sy, int button, int holdMs) {
        move(target, sx, sy);
        button(target, button, true);
        try {
            Thread.sleep(holdMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        button(target, button, false);
    }

    /**
     * Wheel notches at the pointer; positive is up. {@code WM_MOUSEWHEEL} is the one mouse message whose point is
     * in screen coordinates, not the window's.
     */
    synchronized void wheel(HWND target, int notches) {
        if (position() == null) {
            return;
        }
        Hit hit = hit(target, x, y);
        if (hit == null) {
            return;
        }
        for (int i = 0; i < Math.abs(notches); i++) {
            post(hit.window(), User32.WM_MOUSEWHEEL, WindowMessages.wheelWParam(notches > 0, held),
                    WindowMessages.pointLParam(x, y));
        }
    }

    /**
     * A key's press or release, posted to {@code window} with its scan code in the {@code lParam}. Alt, F10 and
     * any key while a posted Alt is down go as {@code WM_SYSKEYDOWN/UP}, the way Windows delivers them, or the
     * window's menu and Alt+Enter handling never sees them.
     */
    synchronized void key(HWND window, int vk, boolean press) {
        ScanCode scan = ScanCode.of(vk, User32.INSTANCE.MapVirtualKeyW(vk, User32.MAPVK_VK_TO_VSC_EX));
        if (WindowMessages.isAlt(vk)) {
            altHeld = press;
        }
        boolean alt = altHeld || (WindowMessages.isAlt(vk) && !press);
        post(window, WindowMessages.keyMessage(vk, press, alt), vk,
                WindowMessages.keyLParam(scan, !press, alt));
    }

    /** {@code text} as {@code WM_CHAR}s: the character itself, so the target's layout and shift state don't matter. */
    static void type(HWND window, String text) {
        for (int i = 0; i < text.length(); i++) {
            post(window, User32.WM_CHAR, text.charAt(i), WindowMessages.keyLParam(ScanCode.NONE, false, false));
        }
    }

    private void place(int sx, int sy) {
        x = sx;
        y = sy;
        placed = true;
    }

    /** A window and a point in its client coordinates. */
    private record Hit(HWND window, int x, int y) {}

    /**
     * The deepest visible child under screen {@code (sx, sy)}, starting from {@code target} when the point is
     * inside it and from the topmost window there otherwise; {@code null} when there is no window at all.
     */
    private static Hit hit(HWND target, int sx, int sy) {
        HWND start = start(target, sx, sy);
        if (start == null) {
            return null;
        }
        HWND window = start;
        for (int depth = 0; depth < 16; depth++) {
            Point client = toClient(window, sx, sy);
            POINT.ByValue at = new POINT.ByValue(client.x, client.y);
            HWND child = User32.INSTANCE.ChildWindowFromPointEx(window, at,
                    User32.CWP_SKIPINVISIBLE | User32.CWP_SKIPTRANSPARENT);
            if (child == null || child.equals(window)) {
                break;
            }
            window = child;
        }
        Point client = toClient(window, sx, sy);
        return new Hit(window, client.x, client.y);
    }

    /** The top-level window a gesture at screen {@code (sx, sy)} goes to, for watching it; {@code null} for none. */
    static HWND topLevelAt(HWND target, int sx, int sy) {
        HWND start = start(target, sx, sy);
        return start == null ? null : User32.INSTANCE.GetAncestor(start, User32.GA_ROOT);
    }

    /** {@code target} when the point is inside it, the topmost window there otherwise. */
    private static HWND start(HWND target, int sx, int sy) {
        return target != null && User32.INSTANCE.IsWindow(target) && contains(target, sx, sy)
                ? target : User32.INSTANCE.WindowFromPoint(new POINT.ByValue(sx, sy));
    }

    private static boolean contains(HWND window, int sx, int sy) {
        Point client = toClient(window, sx, sy);
        RECT rect = new RECT();
        return User32.INSTANCE.GetClientRect(window, rect)
                && client.x >= 0 && client.y >= 0 && client.x < rect.right && client.y < rect.bottom;
    }

    private static Point toClient(HWND window, int sx, int sy) {
        POINT pt = new POINT(sx, sy);
        User32.INSTANCE.ScreenToClient(window, pt);
        return new Point(pt.x, pt.y);
    }

    private static void post(HWND window, int message, long wParam, long lParam) {
        User32.INSTANCE.PostMessage(window, message, new WPARAM(wParam), new LPARAM(lParam));
    }
}
