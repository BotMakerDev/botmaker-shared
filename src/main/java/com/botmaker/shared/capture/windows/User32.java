package com.botmaker.shared.capture.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.win32.StdCallLibrary;

public interface User32 extends StdCallLibrary {


    User32 INSTANCE = Native.load(
            "user32",
            User32.class,
            W32APIOptions.DEFAULT_OPTIONS);
    interface WNDENUMPROC extends StdCallCallback {
        boolean callback(Pointer hWnd, Pointer arg);
    }

    boolean EnumWindows(WNDENUMPROC lpEnumFunc, Pointer arg);
    boolean EnumChildWindows(HWND parent, WNDENUMPROC lpEnumFunc, Pointer arg);

    /* ---------  text / geometry  --------- */

    int  GetWindowTextA(Pointer hWnd, byte[] lpString, int nMax);
    Pointer FindWindowA(String lpClass, String lpName);
    boolean GetWindowRect(Pointer hWnd, RECT rect);
    boolean GetClientRect(HWND hWnd, RECT rect);

    /* ---------  enumeration filtering (alt-tab heuristic)  --------- */

    boolean IsWindowVisible(Pointer hWnd);
    int     GetWindowLongA(Pointer hWnd, int nIndex);   // GWL_EXSTYLE fits in 32 bits, valid on Win64
    Pointer GetWindow(Pointer hWnd, int uCmd);          // GW_OWNER → owning window, null if top-level

    int GWL_EXSTYLE      = -20;
    int WS_EX_TOOLWINDOW = 0x00000080;
    int WS_EX_APPWINDOW  = 0x00040000;
    int GW_OWNER         = 4;

    /* ---------  DC / painting  --------- */

    HDC GetDC(HWND hWnd);
    int ReleaseDC(HWND hWnd, HDC hDC);
    boolean PrintWindow(HWND hWnd, HDC hdcBlt, int flags);

    /* ---------  DPI / focus / mouse pos  --------- */

    /** Windows 10 1703+; false when the process's awareness is already fixed (a manifest, or AWT got there first). */
    boolean SetProcessDpiAwarenessContext(Pointer value);
    /** Windows 10 1607+; returns the thread's previous context, or null when {@code value} is not valid. */
    Pointer SetThreadDpiAwarenessContext(Pointer value);
    Pointer GetThreadDpiAwarenessContext();
    /** 0 unaware, 1 system aware, 2 per-monitor aware (v1 or v2), -1 invalid. */
    int     GetAwarenessFromDpiAwarenessContext(Pointer value);
    HWND    GetForegroundWindow();
    boolean SetForegroundWindow(HWND hWnd);
    boolean GetCursorPos(POINT pt);
    short   GetAsyncKeyState(int vKey);

    /* ---------  coordinate helpers  --------- */

    boolean ClientToScreen(HWND hWnd, POINT pt);
    boolean ScreenToClient(HWND hWnd, POINT pt);

    /* ---------  hit-testing  --------- */
    HWND WindowFromPoint(POINT pt);
    HWND WindowFromPoint(POINT.ByValue pt);   // ← ByValue !
    int  CWP_ALL = 0x0000;
    int  CWP_SKIPINVISIBLE = 0x0001;
    int  CWP_SKIPTRANSPARENT = 0x0004;
    /** Takes the point by value, in {@code parent}'s client coordinates; returns {@code parent} itself on no child. */
    HWND ChildWindowFromPointEx(HWND parent, POINT.ByValue pt, int flags);
    boolean IsWindow(HWND hWnd);
    boolean IsIconic(HWND hWnd);
    int  GA_ROOT = 2;

    /* ---------  messaging  --------- */

    boolean PostMessage(HWND hWnd, int msg, WPARAM wp, LPARAM lp);
    LRESULT SendMessage(HWND hWnd, int msg, WPARAM wp, LPARAM lp);

    /* ---- NEW DESKTOP / METRICS ------------------------------------------- */

    HWND GetDesktopWindow();                // top level “Progman/WorkerW” window
    int  GetSystemMetrics(int index);       // screen size, virtual-desktop origin

    int SM_XVIRTUALSCREEN = 76;   // left   of bounding rect (can be negative)
    int SM_YVIRTUALSCREEN = 77;   // top    of bounding rect
    int SM_CXVIRTUALSCREEN = 78;  // width  of bounding rect
    int SM_CYVIRTUALSCREEN = 79;  // height of bounding rect

    HWND GetAncestor(HWND hWnd, int gaFlags);     // GA_ROOT = 2, GA_ROOTOWNER = 3
    boolean ShowWindow(HWND hWnd, int nCmdShow);  // SW_RESTORE = 9

    /* ---------  window move / resize  --------- */

    boolean SetWindowPos(HWND hWnd, HWND hWndInsertAfter, int X, int Y, int cx, int cy, int uFlags);

    int SWP_NOSIZE     = 0x0001;
    int SWP_NOMOVE     = 0x0002;
    int SWP_NOZORDER   = 0x0004;
    int SWP_NOACTIVATE = 0x0010;
    int SW_RESTORE     = 9;

    /* ---------  input synthesis (SendInput lives in jna-platform's User32; see SendInputs)  --------- */

    boolean SetCursorPos(int x, int y);
    /** The virtual key and shift state that type {@code ch} on the current layout, or -1 when none does. */
    short VkKeyScanW(char ch);

    /**
     * Virtual key → scancode. {@code MAPVK_VK_TO_VSC_EX} (4) is the mapping DirectInput/RawInput games read, with
     * the {@code 0xE0}/{@code 0xE1} prefix of an extended key in the high byte; plain {@code MAPVK_VK_TO_VSC}
     * drops it, which turns the arrow keys into the numeric keypad's.
     */
    int MapVirtualKeyW(int uCode, int uMapType);

    int MAPVK_VK_TO_VSC_EX = 4;

    int KEYEVENTF_EXTENDEDKEY = 0x0001;
    int KEYEVENTF_KEYUP      = 0x0002;
    int KEYEVENTF_UNICODE    = 0x0004;
    /** Interpret {@code wScan} as a scancode; without it a game reading raw input sees nothing. */
    int KEYEVENTF_SCANCODE   = 0x0008;

    int MOUSEEVENTF_MOVE      = 0x0001;
    int MOUSEEVENTF_LEFTDOWN  = 0x0002;
    int MOUSEEVENTF_LEFTUP    = 0x0004;
    int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    int MOUSEEVENTF_RIGHTUP   = 0x0010;
    int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    int MOUSEEVENTF_MIDDLEUP   = 0x0040;
    // The side buttons. Unlike every flag above, these two do not say *which* side button on their own —
    // the button travels in mouse_event's dwData as XBUTTON1/XBUTTON2, which is why they need a pair of
    // constants rather than four flags.
    int MOUSEEVENTF_XDOWN      = 0x0080;
    int MOUSEEVENTF_XUP        = 0x0100;
    int XBUTTON1               = 0x0001;
    int XBUTTON2               = 0x0002;
    int MOUSEEVENTF_WHEEL      = 0x0800;

    /* ---------  mouse messages (posted to a specific HWND; encoded by WindowMessages)  --------- */

    int WM_MOUSEMOVE   = 0x0200;
    int WM_LBUTTONDOWN = 0x0201;
    int WM_LBUTTONUP   = 0x0202;
    int WM_RBUTTONDOWN = 0x0204;
    int WM_RBUTTONUP   = 0x0205;
    int WM_MBUTTONDOWN = 0x0207;
    int WM_MBUTTONUP   = 0x0208;
    int WM_MOUSEWHEEL  = 0x020A;
    int WM_XBUTTONDOWN = 0x020B;
    int WM_XBUTTONUP   = 0x020C;
    int WHEEL_DELTA    = 120;

    int MK_LBUTTON     = 0x0001;
    int MK_RBUTTON     = 0x0002;
    int MK_MBUTTON     = 0x0010;
    int MK_XBUTTON1    = 0x0020;
    int MK_XBUTTON2    = 0x0040;

    /* ---------  keyboard messages (targeted, posted to a specific HWND)  --------- */

    int WM_KEYDOWN = 0x0100;
    int WM_KEYUP   = 0x0101;
    int WM_CHAR    = 0x0102;
    int WM_SYSKEYDOWN = 0x0104;
    int WM_SYSKEYUP   = 0x0105;
}
