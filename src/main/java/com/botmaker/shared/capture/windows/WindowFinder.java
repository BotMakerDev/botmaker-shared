package com.botmaker.shared.capture.windows;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;

public class WindowFinder {

    public static HWND findWindow(String title) {
        return new HWND(User32.INSTANCE.FindWindowA(null, title));
    }

    public static List<WindowInfo> getChildWindows(HWND parent) {
        List<WindowInfo> windows = new ArrayList<>();
        User32.INSTANCE.EnumChildWindows(parent, (hWnd, p) -> {
            byte[] windowText = new byte[512];
            User32.INSTANCE.GetWindowTextA(hWnd, windowText, 512);
            String title = new String(windowText).trim();
            if (!title.isEmpty()) {
                windows.add(new WindowInfo(new HWND(hWnd), title));
            }
            return true;
        }, null);
        return windows;
    }

    /**
     * Top-level application windows, filtered to the ones a user would recognise (roughly the alt-tab list).
     * {@code EnumWindows} alone returns 100–200+ handles — invisible shells, cloaked UWP ghosts, tool/palette
     * windows and owned dialogs — so we keep only visible, non-cloaked, non-tool, unowned windows with a
     * non-empty title and a non-zero size (or any window explicitly flagged {@code WS_EX_APPWINDOW}).
     */
    public static List<WindowInfo> getAllWindows() {
        List<WindowInfo> windows = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hWnd, p) -> {
            byte[] windowText = new byte[512];
            User32.INSTANCE.GetWindowTextA(hWnd, windowText, 512);
            String title = new String(windowText).trim();
            if (!title.isEmpty() && isRealAppWindow(hWnd)) {
                windows.add(new WindowInfo(new HWND(hWnd), title));
            }
            return true;
        }, null);
        return windows;
    }

    /** The alt-tab visibility heuristic — see {@link #getAllWindows()}. */
    private static boolean isRealAppWindow(Pointer hWnd) {
        if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
            return false;
        }
        int exStyle = User32.INSTANCE.GetWindowLongA(hWnd, User32.GWL_EXSTYLE);
        boolean appWindow = (exStyle & User32.WS_EX_APPWINDOW) != 0;
        if (!appWindow) {
            // Tool/palette windows and owned dialogs/tooltips are not top-level apps.
            if ((exStyle & User32.WS_EX_TOOLWINDOW) != 0) {
                return false;
            }
            if (User32.INSTANCE.GetWindow(hWnd, User32.GW_OWNER) != null) {
                return false;
            }
        }
        // Cloaked windows (hidden UWP/virtual-desktop ghosts) look "visible" to IsWindowVisible.
        IntByReference cloaked = new IntByReference();
        if (Dwmapi.INSTANCE.DwmGetWindowAttribute(hWnd, Dwmapi.DWMWA_CLOAKED, cloaked, 4) == 0
                && cloaked.getValue() != 0) {
            return false;
        }
        // Zero-size shells contribute nothing capturable.
        RECT rect = new RECT();
        if (!User32.INSTANCE.GetWindowRect(hWnd, rect)
                || rect.right - rect.left <= 0 || rect.bottom - rect.top <= 0) {
            return false;
        }
        return true;
    }
}
