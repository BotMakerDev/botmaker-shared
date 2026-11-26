// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture.windows;

import com.botmaker.shared.capture.PointClick;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.WindowPointer;
import com.botmaker.shared.capture.WindowPointer.Clicked;
import com.botmaker.shared.capture.WindowPointer.Pointed;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of Windows' pointer: which hooked messages are the pick's, and the match by HWND. No user32. */
class WindowsPointerTest {

    @Test
    void onlyTheLeftPressAndItsReleaseAreSwallowed() {
        PointClick watch = new PointClick(Long.MAX_VALUE);
        assertFalse(WindowsPointer.swallowMouse(watch, User32.WM_MOUSEMOVE, 10, 10), "the cursor still moves");
        assertFalse(WindowsPointer.swallowMouse(watch, User32.WM_MOUSEWHEEL, 10, 10));
        assertFalse(WindowsPointer.swallowMouse(watch, User32.WM_RBUTTONDOWN, 10, 10));
        assertTrue(WindowsPointer.swallowMouse(watch, User32.WM_LBUTTONDOWN, 300, 200));
        assertTrue(WindowsPointer.swallowMouse(watch, User32.WM_LBUTTONUP, 300, 200));
        assertEquals(300, watch.pressedAt().orElseThrow().getX());
    }

    @Test
    void escDownCancelsAndOtherKeysPass() {
        PointClick watch = new PointClick(Long.MAX_VALUE);
        assertFalse(WindowsPointer.swallowKey(watch, User32.WM_KEYDOWN, 'A'));
        assertFalse(WindowsPointer.swallowKey(watch, User32.WM_KEYUP, User32.VK_ESCAPE));
        assertTrue(WindowsPointer.swallowKey(watch, User32.WM_KEYDOWN, User32.VK_ESCAPE));
        assertTrue(watch.pressedAt().isEmpty());
    }

    @Test
    void theDesktopAndTheTaskbarAreNoWindow() {
        assertTrue(WindowsPointer.isShell("Progman"));
        assertTrue(WindowsPointer.isShell("WorkerW"));
        assertTrue(WindowsPointer.isShell("Shell_TrayWnd"));
        assertFalse(WindowsPointer.isShell("UnityWndClass"));
    }

    @Test
    void theClickedRootWindowMatchesItsListedWindowByHandle() {
        GenericWindow steam = new GenericWindow(new HWND(new Pointer(0x10)), "Steam", new Rectangle(0, 0, 800, 600),
                1, "steam.exe");
        GenericWindow game = new GenericWindow(new HWND(new Pointer(0x20)), "Hades II",
                new Rectangle(0, 0, 2560, 1440), 2, "Hades2.exe");
        Pointed pointed = WindowPointer.resolve(new Clicked(new HWND(new Pointer(0x20)), null, null, null),
                List.of(steam, game), WindowPointer.NOT_LISTED);
        assertSame(game, ((Pointed.Window) pointed).window());
        assertEquals(new Pointed.Unreadable(WindowPointer.NOT_LISTED), WindowPointer.resolve(
                new Clicked(new HWND(new Pointer(0x30)), null, null, null), List.of(steam, game),
                WindowPointer.NOT_LISTED));
    }
}
