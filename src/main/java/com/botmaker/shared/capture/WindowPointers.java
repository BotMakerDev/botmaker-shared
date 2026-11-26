// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import com.botmaker.shared.capture.linux.KWinPointer;
import com.botmaker.shared.capture.linux.X11Pointer;
import com.botmaker.shared.capture.mac.MacPointer;
import com.botmaker.shared.capture.windows.WindowsPointer;
import com.botmaker.shared.platform.Os;
import com.botmaker.shared.platform.SessionEnv;

import java.util.List;

/**
 * Holds {@link WindowPointer#current()}: Windows' and Mac's own; on Linux KDE when KWin owns its bus name, else X11
 * when no Wayland, else none.
 */
final class WindowPointers {

    static final WindowPointer CURRENT = choose();

    private WindowPointers() {
    }

    private static WindowPointer choose() {
        if (Os.current() == Os.WINDOWS) return new WindowsPointer(WindowPointers::capturable);
        if (Os.current() == Os.MAC) return new MacPointer(WindowPointers::capturable);
        if (Os.current() != Os.LINUX) return WindowPointer.UNAVAILABLE;
        if (KWinPointer.kwinOnBus()) return new KWinPointer(WindowPointers::capturable);
        if (System.getenv(SessionEnv.DISPLAY) != null && System.getenv(SessionEnv.WAYLAND_DISPLAY) == null) {
            return new X11Pointer(System.getenv(SessionEnv.DISPLAY), WindowPointers::capturable);
        }
        return WindowPointer.UNAVAILABLE;
    }

    private static List<GenericWindow> capturable() {
        return NativeControllerFactory.get().getAllWindows();
    }
}
