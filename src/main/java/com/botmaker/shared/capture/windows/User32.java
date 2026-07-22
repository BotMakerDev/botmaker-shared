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

    boolean SetProcessDPIAware();
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
    HWND ChildWindowFromPointEx(HWND parent, POINT pt, int flags);

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

    /* ---------  input synthesis (keybd_event / mouse_event are simple + struct-free)  --------- */

    void keybd_event(byte bVk, byte bScan, int dwFlags, Pointer dwExtraInfo);
    void mouse_event(int dwFlags, int dx, int dy, int dwData, Pointer dwExtraInfo);
    boolean SetCursorPos(int x, int y);
    short VkKeyScanA(byte ch);

    int KEYEVENTF_KEYUP      = 0x0002;

    int MOUSEEVENTF_MOVE      = 0x0001;
    int MOUSEEVENTF_LEFTDOWN  = 0x0002;
    int MOUSEEVENTF_LEFTUP    = 0x0004;
    int MOUSEEVENTF_RIGHTDOWN = 0x0008;
    int MOUSEEVENTF_RIGHTUP   = 0x0010;
    int MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    int MOUSEEVENTF_MIDDLEUP   = 0x0040;
    int MOUSEEVENTF_WHEEL      = 0x0800;

    /* ---------  mouse constants  --------- */

    int WM_LBUTTONDOWN = 0x0201;
    int WM_LBUTTONUP   = 0x0202;
    int MK_LBUTTON     = 0x0001;

    /* ---------  keyboard messages (targeted, posted to a specific HWND)  --------- */

    int WM_KEYDOWN = 0x0100;
    int WM_KEYUP   = 0x0101;
    int WM_CHAR    = 0x0102;
}
