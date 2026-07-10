package com.botmaker.shared.capture.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Minimal binding to the Desktop Window Manager API, used only to detect <b>cloaked</b> windows.
 *
 * <p>Modern (UWP) apps keep hidden ghost top-levels that {@code EnumWindows} still returns — they are
 * invisible not via {@code IsWindowVisible} but via DWM cloaking. {@link #DwmGetWindowAttribute} with
 * {@link #DWMWA_CLOAKED} reports a non-zero value for those, letting {@code WindowFinder} filter them out.
 */
public interface Dwmapi extends StdCallLibrary {

    Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);

    int DWMWA_CLOAKED = 14;

    /** Returns S_OK (0) on success, writing the attribute into {@code pvAttribute}. */
    int DwmGetWindowAttribute(Pointer hWnd, int dwAttribute, IntByReference pvAttribute, int cbAttribute);
}
