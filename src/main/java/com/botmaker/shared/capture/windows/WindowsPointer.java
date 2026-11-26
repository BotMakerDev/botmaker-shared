// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture.windows;

import com.botmaker.shared.capture.PointClick;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.WindowPointer;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinUser;

import java.awt.geom.Point2D;
import java.lang.ref.Reference;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Points on Windows: low-level mouse and keyboard hooks, installed on the calling thread, which waits for input and
 * pumps it with {@code PeekMessage} until the pick is over; the first left press is swallowed and its point goes to
 * {@code WindowFromPoint}, then {@code GetAncestor(GA_ROOT)}, the top-level window. The system cursor is the
 * crosshair while armed, and the user's cursors are reloaded after. Not tried live yet.
 */
public final class WindowsPointer implements WindowPointer {

    /** The longest one wait for input lasts: how late an interrupt or the deadline may be seen. */
    private static final int WAIT_MS = 50;
    /** The desktop's and the taskbar's top-level classes: a click there names no window. */
    private static final Set<String> SHELL_CLASSES = Set.of("Progman", "WorkerW", "Shell_TrayWnd",
            "Shell_SecondaryTrayWnd");
    private static final LRESULT SWALLOW = new LRESULT(1);

    private final Supplier<List<GenericWindow>> capturable;

    public WindowsPointer(Supplier<List<GenericWindow>> capturable) {
        this.capturable = capturable;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Pointed point(Duration timeout) {
        User32 user32 = User32.INSTANCE;
        PointClick watch = new PointClick(System.currentTimeMillis() + timeout.toMillis());
        WinUser.HHOOK[] hooks = new WinUser.HHOOK[2];
        WinUser.LowLevelMouseProc mouse = (nCode, wParam, info) -> {
            if (nCode >= 0 && swallowMouse(watch, wParam.intValue(), info.pt.x, info.pt.y)) return SWALLOW;
            return user32.CallNextHookEx(hooks[0], nCode, wParam, new LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        WinUser.LowLevelKeyboardProc keyboard = (nCode, wParam, info) -> {
            if (nCode >= 0 && swallowKey(watch, wParam.intValue(), info.vkCode)) return SWALLOW;
            return user32.CallNextHookEx(hooks[1], nCode, wParam, new LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        var module = Kernel32.INSTANCE.GetModuleHandle(null);
        boolean crosshair = false;
        try {
            hooks[0] = user32.SetWindowsHookEx(User32.WH_MOUSE_LL, mouse, module, 0);
            hooks[1] = user32.SetWindowsHookEx(User32.WH_KEYBOARD_LL, keyboard, module, 0);
            if (hooks[0] == null || hooks[1] == null) {
                return new Pointed.Unreadable("Windows refused to watch the mouse; try again");
            }
            crosshair = showCrosshair(user32);
            WinUser.MSG msg = new WinUser.MSG();
            while (!watch.over(System.currentTimeMillis())) {
                if (Thread.currentThread().isInterrupted()) return new Pointed.Cancelled();
                // Wakes as soon as input comes, so the hooks never hold the system's input back.
                user32.MsgWaitForMultipleObjects(0, null, false, WAIT_MS, User32.QS_ALLINPUT);
                while (user32.PeekMessage(msg, null, 0, 0, User32.PM_REMOVE)) {
                    // Nothing posted here is ours; the hooks run inside PeekMessage.
                }
            }
        } finally {
            if (hooks[0] != null) user32.UnhookWindowsHookEx(hooks[0]);
            if (hooks[1] != null) user32.UnhookWindowsHookEx(hooks[1]);
            if (crosshair) user32.SystemParametersInfo(User32.SPI_SETCURSORS, 0, null, 0);
            // JNA holds a callback weakly: kept reachable until unhooked, or a GC frees its trampoline.
            Reference.reachabilityFence(mouse);
            Reference.reachabilityFence(keyboard);
        }
        Optional<Point2D> at = watch.pressedAt();
        if (at.isEmpty()) return new Pointed.Cancelled();
        HWND root = windowAt((int) at.get().getX(), (int) at.get().getY());
        if (root == null || isShell(className(root))) {
            return new Pointed.Unreadable("That's the desktop — point at the game's window");
        }
        return WindowPointer.resolve(new Clicked(root, null, null, null), capturable.get(), NOT_LISTED);
    }

    /** Whether a low-level mouse message is the pick's, which the hook swallows: left press and its release. */
    static boolean swallowMouse(PointClick watch, int message, int x, int y) {
        return switch (message) {
            case User32.WM_LBUTTONDOWN -> watch.press(x, y, System.currentTimeMillis());
            case User32.WM_LBUTTONUP -> watch.release();
            default -> false;
        };
    }

    /** Whether a low-level key message is Esc before the press, which cancels and is swallowed. */
    static boolean swallowKey(PointClick watch, int message, int vkCode) {
        return (message == User32.WM_KEYDOWN || message == User32.WM_SYSKEYDOWN) && vkCode == User32.VK_ESCAPE
                && watch.escape();
    }

    /** Whether a top-level window's class is the desktop's or the taskbar's. */
    static boolean isShell(String windowClass) {
        return SHELL_CLASSES.contains(windowClass);
    }

    private static String className(HWND hwnd) {
        char[] name = new char[256];
        int n = User32.INSTANCE.GetClassName(hwnd, name, name.length);
        return new String(name, 0, Math.max(n, 0));
    }

    /** The top-level window under a point in physical pixels, as the hook reports it. */
    private static HWND windowAt(int x, int y) {
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            HWND under = User32.INSTANCE.WindowFromPoint(new POINT.ByValue(x, y));
            return under == null ? null : User32.INSTANCE.GetAncestor(under, User32.GA_ROOT);
        }
    }

    /** The crosshair as the normal arrow; true when set, so the user's cursors are reloaded after. */
    private static boolean showCrosshair(User32 user32) {
        Pointer cross = user32.LoadCursor(null, new Pointer(User32.IDC_CROSS));
        Pointer copy = cross == null ? null : user32.CopyIcon(cross);
        if (copy == null) return false;
        if (user32.SetSystemCursor(copy, User32.OCR_NORMAL)) return true;
        user32.DestroyCursor(copy); // not taken over
        return false;
    }
}
