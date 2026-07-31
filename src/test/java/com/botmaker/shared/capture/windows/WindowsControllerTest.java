package com.botmaker.shared.capture.windows;

import com.sun.jna.platform.win32.WinDef.HWND;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code capture.windows} is <b>0.0% covered across 251 lines</b> and is half of {@link
 * com.botmaker.shared.capture.NativeController}'s implementations. This class exists partly to raise that and
 * partly to <b>say why the rest of it stays zero</b>, which is the more useful half: an unexplained 0.0% reads
 * as neglect, and the next person re-derives the reason before deciding not to fix it.
 *
 * <p>The split is structural, not a matter of effort. {@link WindowFinder}'s alt-tab filter is real logic —
 * four rules over {@code WS_EX_TOOLWINDOW}, {@code GW_OWNER}, {@code DWMWA_CLOAKED} and the window rect — but
 * it reads them through {@code User32.INSTANCE} and {@code Dwmapi.INSTANCE}, JNA singletons that bind to
 * {@code user32.dll} at class-load time. On Linux the load throws before any rule runs, so there is nothing to
 * assert against short of a Windows host or a seam that does not exist today. Those tests are therefore
 * {@link EnabledOnOs @EnabledOnOs(WINDOWS)} and <b>will not run in CI until there is a Windows runner</b> —
 * a known, recorded gap rather than a silent one.
 *
 * <p>What is verifiable everywhere: the constants the rules are written in terms of (a typo in one silently
 * inverts a filter), and {@link WindowInfo}'s value behaviour.
 */
class WindowsControllerTest {

    // ---- Pure: the constants the filter is expressed in. Verifiable on any OS. ----

    /**
     * These are Win32 ABI values, not choices. A wrong one does not fail to compile and does not throw — it
     * silently changes which windows the picker offers, which is indistinguishable from a filter bug.
     */
    @Test
    void win32ConstantsMatchTheAbi() {
        assertEquals(-20, User32.GWL_EXSTYLE, "GWL_EXSTYLE");
        assertEquals(0x00000080, User32.WS_EX_TOOLWINDOW, "WS_EX_TOOLWINDOW");
        assertEquals(0x00040000, User32.WS_EX_APPWINDOW, "WS_EX_APPWINDOW");
        assertEquals(4, User32.GW_OWNER, "GW_OWNER");
        assertEquals(14, Dwmapi.DWMWA_CLOAKED, "DWMWA_CLOAKED");
    }

    /**
     * The two extended-style bits must not overlap — {@code WS_EX_APPWINDOW} overrides the tool-window and
     * owner rules, so if a single style value could set both, "explicitly an app window" and "a palette" would
     * be the same window and the override would be unreachable.
     */
    @Test
    void appWindowAndToolWindowAreDistinctBits() {
        assertEquals(0, User32.WS_EX_APPWINDOW & User32.WS_EX_TOOLWINDOW);

        int toolPalette = User32.WS_EX_TOOLWINDOW;
        int forcedAppWindow = User32.WS_EX_TOOLWINDOW | User32.WS_EX_APPWINDOW;
        assertTrue((toolPalette & User32.WS_EX_APPWINDOW) == 0, "a plain tool window is not an app window");
        assertTrue((forcedAppWindow & User32.WS_EX_APPWINDOW) != 0,
                "WS_EX_APPWINDOW must survive being combined with WS_EX_TOOLWINDOW — that combination is "
                        + "exactly the case the filter's override exists for");
    }

    // ---- WindowInfo: a value type over an opaque handle. ----

    @Test
    void windowInfoCarriesTheHandleAndTitleItWasGiven() {
        HWND handle = new HWND();
        WindowInfo info = new WindowInfo(handle, "Game");

        assertSame(handle, info.getHWnd(), "the handle must not be copied — identity is what makes it a handle");
        assertEquals("Game", info.getTitle());
    }

    /**
     * {@code getAllWindows} trims the fixed 512-byte {@code GetWindowTextA} buffer and drops empty titles, so a
     * {@code WindowInfo} that reaches a caller always has a non-blank title. Nothing enforces that at
     * construction; this pins the property the callers rely on rather than the check that produces it.
     */
    @Test
    void windowInfoDoesNotNormaliseTitles() {
        assertEquals("  padded  ", new WindowInfo(new HWND(), "  padded  ").getTitle(),
                "WindowInfo is a carrier — the trimming belongs to WindowFinder, and moving it here would "
                        + "hide a blank title from the filter that is supposed to reject it");
    }

    // ---- The JNA paths. Windows-only, by construction. ----

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void enumeratingTopLevelWindowsReturnsTitledWindows() {
        List<WindowInfo> windows = WindowFinder.getAllWindows();

        assertNotNull(windows);
        for (WindowInfo w : windows) {
            assertFalse(w.getTitle().isBlank(), "getAllWindows returned a blank-titled window: it must filter those");
            assertNotNull(w.getHWnd());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void findWindowOnANameNothingOwnsYieldsANullHandle() {
        HWND missing = WindowFinder.findWindow("botmaker-no-such-window-" + System.nanoTime());

        // FindWindowA returns NULL; the wrapper still boxes it, so the pointer inside is what to check.
        assertTrue(missing == null || missing.getPointer() == null,
                "a window title nothing owns must not resolve to a usable handle");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void childEnumerationOfANonWindowIsEmptyRatherThanThrowing() {
        assertTrue(WindowFinder.getChildWindows(new HWND()).isEmpty(),
                "enumeration over a null parent must degrade to empty — Studio's picker walks handles that "
                        + "can close between the enumerate and the walk");
    }
}
