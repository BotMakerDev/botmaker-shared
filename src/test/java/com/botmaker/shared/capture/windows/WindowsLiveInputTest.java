package com.botmaker.shared.capture.windows;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.game.GameLibraries;
import com.botmaker.shared.game.GameLibraryProvider;
import com.botmaker.shared.game.InstalledGame;
import com.botmaker.shared.ipc.TelemetryEvent;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HBRUSH;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Windows input and capture paths against a real window: what the manual Windows checklist asks of game A,
 * the ignored-click warning, and the take-over path, each checked by the messages the window receives.
 *
 * <p>The stand-in is a bare Win32 window with a title bar, recording every mouse and key message the way a game's
 * window procedure would see it, and repainting on each click (the "deaf" one never repaints: a game that reads
 * raw input, as far as {@link IgnoredClickWatch} can tell). It is in the test's own process; posted messages and
 * {@code SendInput} reach it exactly as they would reach another's.
 *
 * <p><b>Opt-in</b> ({@code -Dbotmaker.live=true}, Windows only): the take-over tests move the real cursor and type
 * on the real keyboard. The stand-in stays topmost so a gesture at a bare screen point lands on it, and no key is
 * sent unless the stand-in is the foreground window — a keystroke must never reach the window the user works in.
 * The run order is fixed: background first (the cursor must not move), then Windows.Graphics.Capture (a crash
 * there takes the JVM) and the launcher listing, take-over last.
 *
 * <p>What it cannot stand in for: a DirectX game reading raw input, another display scale or a second monitor, an
 * elevated game, and an AltGr layout unless the machine has one. Those stay manual.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "botmaker.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WindowsLiveInputTest {

    private static final int FILL = 0x00A05020;        // COLORREF, 0x00BBGGRR
    private static final int FILL_CLICKED = 0x002080F0;
    private static final int COVER = 0x0000C000;
    private static final int VK_LEFT = 0x25;
    private static final int VK_RETURN = 0x0D;
    private static final int VK_MENU = 0x12;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x11;
    private static final int SM_CXSCREEN = 0;
    private static final int SM_CYSCREEN = 1;

    private StandIn game;
    private StandIn deaf;
    private Point cursorAtStart;

    @BeforeAll
    void open() throws InterruptedException {
        new WindowsController(); // declares the process DPI-aware before any window exists
        game = StandIn.open("BotMaker live stand-in " + ProcessHandle.current().pid(), 120, 120, 480, 360, true);
        deaf = StandIn.open("BotMaker deaf stand-in " + ProcessHandle.current().pid(), 640, 120, 320, 240, false);
        cursorAtStart = cursor();
        System.out.printf("[live] scale %d%%, keyboard layout %s, client %s%n",
                Win.INSTANCE.GetDpiForWindow(game.hwnd) * 100 / 96, layout(), WindowsController.clientRect(game.hwnd));
    }

    @AfterAll
    void close() {
        Diag.setSink(null);
        System.clearProperty(WgcCapture.PROPERTY);
        if (game != null) game.close();
        if (deaf != null) deaf.close();
    }

    // --- §1 background input ---

    @Test
    @Order(2)
    void theWindowsRectIsItsClientAreaSoClicksDoNotLandATitleBarHigh() {
        GenericWindow window = find(new WindowsController(), game);
        Rectangle client = WindowsController.clientRect(game.hwnd);
        RECT outer = new RECT();
        User32.INSTANCE.GetWindowRect(game.hwnd.getPointer(), outer);
        assertEquals(client, window.getRect(), "the window's rect is its client area");
        assertTrue(client.y - outer.top >= 20, "the stand-in has a title bar above its client area");

        WindowsController ctl = new WindowsController();
        game.clear();
        ctl.postLeftClick(window, 40, 30);
        game.awaitAt(User32.WM_LBUTTONDOWN, 40, 30);
        game.awaitAt(User32.WM_LBUTTONUP, 40, 30);

        game.clear();
        ctl.click(client.x + 200, client.y + 150, 1);
        game.awaitAt(User32.WM_LBUTTONDOWN, 200, 150);
    }

    @Test
    @Order(3)
    void everyButtonLandsOnTheSpot() {
        WindowsController ctl = new WindowsController();
        Rectangle client = WindowsController.clientRect(game.hwnd);
        record Expect(int button, int down, int xButton) {}
        for (Expect e : List.of(new Expect(1, User32.WM_LBUTTONDOWN, 0), new Expect(3, User32.WM_RBUTTONDOWN, 0),
                new Expect(2, User32.WM_MBUTTONDOWN, 0), new Expect(8, User32.WM_XBUTTONDOWN, 1),
                new Expect(9, User32.WM_XBUTTONDOWN, 2))) {
            game.clear();
            ctl.click(client.x + 60 + e.button() * 10, client.y + 70, e.button());
            Msg down = game.awaitAt(e.down(), 60 + e.button() * 10, 70);
            if (e.xButton() != 0) {
                assertEquals(e.xButton(), (int) (down.wParam >> 16) & 0xFFFF, "side button " + e.button());
            }
            game.awaitAt(e.down() + 1, 60 + e.button() * 10, 70);
        }
    }

    @Test
    @Order(4)
    void aDragIsAPressAMoveWithTheButtonHeldAndARelease() {
        WindowsController ctl = new WindowsController();
        Rectangle client = WindowsController.clientRect(game.hwnd);
        game.clear();
        ctl.mouseMove(client.x + 50, client.y + 50);
        ctl.mouseButton(1, true);
        ctl.mouseMove(client.x + 250, client.y + 180);
        ctl.mouseButton(1, false);
        game.awaitAt(User32.WM_LBUTTONDOWN, 50, 50);
        game.await("a move to (250,180) with the left button held", m -> m.msg == User32.WM_MOUSEMOVE
                && m.at().equals(new Point(250, 180)) && (m.wParam & User32.MK_LBUTTON) != 0);
        game.awaitAt(User32.WM_LBUTTONUP, 250, 180);
    }

    @Test
    @Order(5)
    void anArrowKeyIsAnArrowInTheBackground() {
        WindowsController ctl = new WindowsController();
        GenericWindow window = find(ctl, game);
        game.clear();
        ctl.keyDown(window, VK_LEFT);
        ctl.keyUp(window, VK_LEFT);
        Msg arrow = game.await("WM_KEYDOWN VK_LEFT", m -> m.msg == User32.WM_KEYDOWN && m.wParam == VK_LEFT);
        assertTrue((arrow.lParam & (1L << 24)) != 0, "the arrow carries the extended bit: not number-pad 4");
    }

    @Test
    @Order(6)
    void altEnterAndTextReachTheWindowInTheBackground() {
        WindowsController ctl = new WindowsController();
        GenericWindow window = find(ctl, game);
        game.clear();
        ctl.keyDown(window, VK_MENU);
        ctl.keyDown(window, VK_RETURN);
        ctl.keyUp(window, VK_RETURN);
        ctl.keyUp(window, VK_MENU);
        Msg enter = game.await("WM_SYSKEYDOWN VK_RETURN",
                m -> m.msg == User32.WM_SYSKEYDOWN && m.wParam == VK_RETURN);
        assertTrue((enter.lParam & (1L << 29)) != 0, "Alt+Enter carries the Alt context bit");

        game.clear();
        ctl.typeText(window, "Hé@1");
        game.awaitText("Hé@1");
    }

    @Test
    @Order(7)
    void aCoveredWindowStillGetsItsClickAndIsNeverCapturedAsTheCover() throws InterruptedException {
        WindowsController ctl = new WindowsController();
        GenericWindow window = find(ctl, game);
        Rectangle client = WindowsController.clientRect(game.hwnd);
        StandIn cover = StandIn.cover(client, COVER);
        try {
            game.clear();
            ctl.postLeftClick(window, 100, 100);
            game.awaitAt(User32.WM_LBUTTONDOWN, 100, 100);
            assertTrue(cover.received().isEmpty(), "the cover got none of the click");

            BufferedImage frame = WindowCapture.capture(game.hwnd);
            assertNotNull(frame);
            int center = frame.getRGB(frame.getWidth() / 2, frame.getHeight() / 2) & 0xFFFFFF;
            System.out.printf("[live] covered capture: center %06X (game %06X/%06X, cover %06X)%n", center,
                    rgb(FILL), rgb(FILL_CLICKED), rgb(COVER));
            assertFalse(near(center, rgb(COVER)), "a covered window is the game or black, never the cover");
        } finally {
            cover.close();
        }
    }

    @Test
    @Order(8)
    void aCaptureIsTheClientAreaInPhysicalPixels() {
        Rectangle client = WindowsController.clientRect(game.hwnd);
        BufferedImage frame = WindowCapture.capture(game.hwnd);
        assertNotNull(frame);
        assertEquals(client.getSize(), new java.awt.Dimension(frame.getWidth(), frame.getHeight()));
        int top = frame.getRGB(frame.getWidth() / 2, 1) & 0xFFFFFF;
        assertTrue(near(top, rgb(FILL)) || near(top, rgb(FILL_CLICKED)),
                String.format("the top row is the client's fill, not a title bar: %06X", top));
    }

    @Test
    @Order(9)
    void aFullscreenGameIsCapturedAndClickedUnderTheRunOverlay() throws InterruptedException {
        Rectangle screen;
        try (WindowsDpi.Scope dpi = WindowsDpi.physical()) {
            screen = new Rectangle(0, 0, User32.INSTANCE.GetSystemMetrics(SM_CXSCREEN),
                    User32.INSTANCE.GetSystemMetrics(SM_CYSCREEN)); // the primary screen
        }
        StandIn full = StandIn.fullscreen("BotMaker fullscreen stand-in " + ProcessHandle.current().pid(), screen);
        StandIn box = null;
        try {
            WindowsController ctl = new WindowsController();
            GenericWindow window = find(ctl, full);
            assertEquals(screen, window.getRect(), "a fullscreen window's rect is the whole screen");
            BufferedImage frame = WindowCapture.capture(full.hwnd);
            assertNotNull(frame);
            assertEquals(screen.getSize(), new java.awt.Dimension(frame.getWidth(), frame.getHeight()));
            int center = frame.getRGB(frame.getWidth() / 2, frame.getHeight() / 2) & 0xFFFFFF;
            assertTrue(near(center, rgb(FILL)), String.format("the fullscreen game itself: %06X", center));

            // the SDK's run overlay: a click-through topmost window drawing boxes over the game
            Point mid = new Point(screen.x + screen.width / 2, screen.y + screen.height / 2);
            box = StandIn.overlayBox(new Rectangle(mid.x - 100, mid.y - 50, 200, 100), COVER);
            full.clear();
            ctl.click(mid.x, mid.y, 1);
            full.awaitAt(User32.WM_LBUTTONDOWN, mid.x - screen.x, mid.y - screen.y);
            assertTrue(box.received().isEmpty(), "the overlay box let the click through");

            // WindowFromPoint skips a click-through window, so the game counts as on top and is screen-copied with
            // the box in it (ROADMAP); an overlay excluded from capture is left out of that copy
            int there = centerUnder(full, mid, screen);
            System.out.printf("[live] fullscreen under the overlay: %06X (game %06X, overlay box %06X)%n", there,
                    rgb(FILL), rgb(COVER));
            assertTrue(near(there, rgb(COVER)), "the box shows in the screen copy: the leak to rule out");
            assumeTrue(Win.INSTANCE.SetWindowDisplayAffinity(box.hwnd, WDA_EXCLUDEFROMCAPTURE),
                    "excluding a window from capture needs Windows 10 2004 or later");
            Thread.sleep(200);
            int excluded = centerUnder(full, mid, screen);
            System.out.printf("[live] ... with the overlay excluded from capture: %06X%n", excluded);
            assertFalse(near(excluded, rgb(COVER)), "an overlay excluded from capture is not in the game's frame");
        } finally {
            if (box != null) box.close();
            full.close();
        }
    }

    private static int centerUnder(StandIn full, Point mid, Rectangle screen) {
        BufferedImage frame = WindowCapture.capture(full.hwnd);
        assertNotNull(frame);
        return frame.getRGB(mid.x - screen.x, mid.y - screen.y) & 0xFFFFFF;
    }

    @Test
    @Order(11)
    void theCursorNeverMovedInTheBackground() {
        assertEquals(cursorAtStart, cursor(), "background input left the real cursor where it was");
    }

    // --- §2 the ignored-click warning ---

    @Test
    @Order(10)
    void clicksAWindowIgnoresWarnOnceAndClicksItAnswersNever() throws InterruptedException {
        List<TelemetryEvent.Log> warnings = new CopyOnWriteArrayList<>();
        Diag.setSink(line -> {
            if (TelemetryEvent.Log.WARN.equals(line.level()) && line.text().contains("changed nothing")) {
                warnings.add(line);
            }
        });
        try {
            WindowsController onDeaf = new WindowsController();
            GenericWindow deafWindow = find(onDeaf, deaf);
            for (int i = 0; i < IgnoredClickWatch.CLICKS + 2; i++) {
                onDeaf.postLeftClick(deafWindow, 30 + i * 20, 40);
                Thread.sleep(IgnoredClickWatch.SETTLE_MS + 150);
            }
            Thread.sleep(IgnoredClickWatch.SETTLE_MS * 2);
            assertTrue(deaf.received().stream().anyMatch(m -> m.msg == User32.WM_LBUTTONDOWN),
                    "the clicks reached the deaf window; it just changed nothing");
            assertEquals(1, warnings.size(), "one warning for the window that ignores clicks: " + warnings);
            assertTrue(warnings.getFirst().text().contains("Take over the mouse and keyboard"));
            assertTrue(warnings.getFirst().text().contains(deaf.title));

            warnings.clear();
            WindowsController onGame = new WindowsController();
            GenericWindow gameWindow = find(onGame, game);
            for (int i = 0; i < IgnoredClickWatch.CLICKS; i++) {
                onGame.postLeftClick(gameWindow, 30 + i * 20, 200);
                Thread.sleep(IgnoredClickWatch.SETTLE_MS + 150);
            }
            Thread.sleep(IgnoredClickWatch.SETTLE_MS * 2);
            assertEquals(List.of(), warnings, "no warning for a window that answers its clicks");
        } finally {
            Diag.setSink(null);
        }
    }

    // --- §5 Windows.Graphics.Capture (opt-in) ---

    @Test
    @Order(20)
    void windowsGraphicsCaptureSeesACoveredWindowAndFollowsAResize() throws InterruptedException {
        System.setProperty(WgcCapture.PROPERTY, "wgc");
        Rectangle client = WindowsController.clientRect(game.hwnd);
        StandIn cover = StandIn.cover(client, COVER);
        try {
            BufferedImage frame = WgcCapture.capture(game.hwnd);
            assertNotNull(frame, "Windows.Graphics.Capture gave no frame (see the run's output for why)");
            assertEquals(client.width, frame.getWidth());
            assertEquals(client.height, frame.getHeight());
            int center = frame.getRGB(frame.getWidth() / 2, frame.getHeight() / 2) & 0xFFFFFF;
            assertTrue(near(center, rgb(FILL)) || near(center, rgb(FILL_CLICKED)),
                    String.format("the covered game itself, not the cover or black: %06X", center));

            game.resizeClient(client.width - 80, client.height - 60);
            Thread.sleep(300);
            Rectangle resized = WindowsController.clientRect(game.hwnd);
            BufferedImage after = null;
            for (int i = 0; i < 10 && (after == null || after.getWidth() != resized.width
                    || after.getHeight() != resized.height); i++) {
                after = WgcCapture.capture(game.hwnd); // not WindowCapture: its PrintWindow rung would pass alone
                Thread.sleep(100);
            }
            assertNotNull(after, "no frame after the resize");
            assertEquals(resized.getSize(), new java.awt.Dimension(after.getWidth(), after.getHeight()),
                    "the capture follows the resize");
        } finally {
            cover.close();
            System.clearProperty(WgcCapture.PROPERTY);
            game.resizeClient(client.width, client.height);
        }
    }

    // --- §7 the 🎮 dialog's libraries ---

    @Test
    @Order(25)
    void listsTheInstalledLaunchersGames() {
        for (GameLibraryProvider provider : GameLibraries.all()) {
            List<InstalledGame> games = provider.installedGames();
            System.out.printf("[live] %s: %d game(s) %s%n", provider.displayName(), games.size(),
                    games.stream().map(InstalledGame::name).limit(8).toList());
        }
    }

    // --- §3 take-over: the real cursor and keyboard ---

    @Test
    @Order(29)
    void aBackgroundScrollAsTheFirstGestureGoesWhereTheCursorIs() {
        Rectangle client = WindowsController.clientRect(game.hwnd);
        Point origin = cursor();
        try {
            // Before any move the posted pointer is the real cursor: a first scroll goes to what is under it.
            Point over = new Point(client.x + client.width / 2, client.y + client.height / 2);
            User32.INSTANCE.SetCursorPos(over.x, over.y);
            assumeTrue(game.isTopAt(over), "something is above the stand-in: skipped");
            WindowsController ctl = new WindowsController();
            game.clear();
            ctl.scroll(1);
            game.await("WM_MOUSEWHEEL up", m -> m.msg == User32.WM_MOUSEWHEEL && (short) (m.wParam >> 16) == 120);
        } finally {
            User32.INSTANCE.SetCursorPos(origin.x, origin.y);
        }
    }

    @Test
    @Order(30)
    void takeOverClicksDragsAndScrolls() throws InterruptedException {
        WindowsController ctl = new WindowsController();
        ctl.useReliableInput();
        GenericWindow window = find(ctl, game);
        Rectangle client = WindowsController.clientRect(game.hwnd);
        Point origin = cursor();
        try {
            assumeTrue(game.isTopAt(new Point(client.x + 100, client.y + 100)),
                    "something is above the stand-in: a real click would land there, skipped");
            game.clear();
            ctl.postLeftClick(window, 100, 100);
            game.awaitAt(User32.WM_LBUTTONDOWN, 100, 100);
            game.awaitAt(User32.WM_LBUTTONUP, 100, 100);
            assertEquals(origin, cursor(), "the cursor came back after the click");

            Thread.sleep(200);
            HWND front = User32.INSTANCE.GetForegroundWindow();
            assumeTrue(game.hwnd.equals(front), "the stand-in did not become the foreground window (it is \""
                    + title(front) + "\"): no key is sent anywhere else, keys skipped");

            game.clear();
            ctl.mouseMove(client.x + 60, client.y + 60);
            ctl.mouseButton(1, true);
            Thread.sleep(50);
            ctl.mouseMove(client.x + 220, client.y + 160);
            Thread.sleep(50);
            ctl.mouseButton(1, false);
            game.awaitAt(User32.WM_LBUTTONDOWN, 60, 60);
            game.await("a move to (220,160) with the left button held", m -> m.msg == User32.WM_MOUSEMOVE
                    && m.at().equals(new Point(220, 160)) && (m.wParam & User32.MK_LBUTTON) != 0);
            game.awaitAt(User32.WM_LBUTTONUP, 220, 160);

            game.clear();
            ctl.scroll(-1);
            game.await("WM_MOUSEWHEEL down", m -> m.msg == User32.WM_MOUSEWHEEL && (short) (m.wParam >> 16) == -120);

            Point before = cursor();
            ctl.mouseMoveRelative(40, 0);
            Thread.sleep(100);
            assertTrue(cursor().x > before.x, "a relative move moved the pointer right");
        } finally {
            User32.INSTANCE.SetCursorPos(origin.x, origin.y);
        }
    }

    @Test
    @Order(31)
    void aTakenOverArrowKeyIsAnArrow() {
        WindowsController ctl = new WindowsController();
        ctl.useReliableInput();
        GenericWindow window = find(ctl, game);
        requireFront();
        game.clear();
        ctl.keyDown(window, VK_LEFT);
        ctl.keyUp(window, VK_LEFT);
        Msg arrow = game.await("WM_KEYDOWN VK_LEFT", m -> m.msg == User32.WM_KEYDOWN && m.wParam == VK_LEFT);
        assertTrue((arrow.lParam & (1L << 24)) != 0, "the arrow carries the extended bit: not number-pad 4");
    }

    @Test
    @Order(32)
    void takeOverTypesAltGrCharactersAndOnesOffTheLayout() {
        WindowsController ctl = new WindowsController();
        ctl.useReliableInput();
        GenericWindow window = find(ctl, game);
        String offLayout = offLayoutCharacter();
        String text = "a@b" + offLayout;
        requireFront();
        game.clear();
        ctl.typeText(window, text);
        boolean altGr = (SendInputs.modifiers(User32.INSTANCE.VkKeyScanW('@')).size() >= 2);
        System.out.printf("[live] take-over typing on layout %s: '@' %s, %s%n", layout(),
                altGr ? "through AltGr" : "without AltGr (this layout can't check AltGr)",
                offLayout.isEmpty() ? "no off-layout character to check" : "an off-layout character (U+"
                        + Integer.toHexString(offLayout.charAt(0)).toUpperCase() + ")");
        game.awaitText(text);
    }

    // --- helpers ---

    /** No key is sent unless the stand-in has the keyboard: it would land in the window the user works in. */
    private void requireFront() {
        HWND front = User32.INSTANCE.GetForegroundWindow();
        assumeTrue(game.hwnd.equals(front), "the stand-in is not the foreground window (\"" + title(front)
                + "\" is): no key is sent anywhere else, skipped");
    }

    private static GenericWindow find(WindowsController ctl, StandIn standIn) {
        return ctl.getAllWindows().stream()
                .filter(w -> standIn.title.equals(w.getTitle()))
                .findFirst()
                .orElseGet(() -> fail("the window list does not offer \"" + standIn.title + "\""));
    }

    private static Point cursor() {
        POINT pt = new POINT();
        assertTrue(User32.INSTANCE.GetCursorPos(pt));
        return new Point(pt.x, pt.y);
    }

    private static String title(HWND hwnd) {
        if (hwnd == null) return "";
        char[] text = new char[256];
        Win.INSTANCE.GetWindowTextW(hwnd, text, text.length);
        return Native.toString(text);
    }

    private static String layout() {
        char[] name = new char[9];
        return Win.INSTANCE.GetKeyboardLayoutNameW(name) ? Native.toString(name) : "?";
    }

    /** A character with no key on the current layout, which only a Unicode keystroke can type. */
    private static String offLayoutCharacter() {
        for (char c : "ж漢ŋ€ß".toCharArray()) {
            if (User32.INSTANCE.VkKeyScanW(c) == -1) {
                return String.valueOf(c);
            }
        }
        return "";
    }

    private static int rgb(int colorref) {
        return ((colorref & 0xFF) << 16) | (colorref & 0xFF00) | ((colorref >> 16) & 0xFF);
    }

    private static boolean near(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) > 24) return false;
        }
        return true;
    }

    /** One message as the window procedure got it. */
    record Msg(int msg, long wParam, long lParam) {
        Point at() {
            return new Point((short) (lParam & 0xFFFF), (short) ((lParam >> 16) & 0xFFFF));
        }

        @Override
        public String toString() {
            return String.format("0x%04X w=%X l=%X", msg, wParam, lParam);
        }
    }

    /** A top-level Win32 window on a thread of its own, recording its input. */
    static final class StandIn {
        private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
        private static final int WS_POPUP = 0x80000000;
        private static final int WS_VISIBLE = 0x10000000;
        private static final int WS_EX_TOPMOST = 0x00000008;
        private static final int WS_EX_NOACTIVATE = 0x08000000;
        private static final int WS_EX_TOOLWINDOW = 0x00000080;
        private static final int WS_EX_LAYERED = 0x00080000;
        private static final int WS_EX_TRANSPARENT = 0x00000020;
        private static final int LWA_ALPHA = 0x2;
        private static final int SW_SHOWNOACTIVATE = 4;
        private static final int WM_DESTROY = 0x0002;
        private static final int WM_CLOSE = 0x0010;
        private static final int WM_ERASEBKGND = 0x0014;

        final String title;
        private final boolean repaints;
        private final int color;
        private final List<Msg> received = new CopyOnWriteArrayList<>();
        private volatile boolean clicked;
        private volatile HWND hwnd;
        private volatile int createError;
        private Thread loop;
        private WinUser.WindowProc proc; // held: the native side keeps only the pointer

        private StandIn(String title, boolean repaints, int color) {
            this.title = title;
            this.repaints = repaints;
            this.color = color;
        }

        static StandIn open(String title, int x, int y, int width, int height, boolean repaints)
                throws InterruptedException {
            StandIn s = new StandIn(title, repaints, FILL);
            s.start(WS_EX_TOPMOST, WS_OVERLAPPEDWINDOW | WS_VISIBLE, x, y, width, height);
            return s;
        }

        /** A borderless topmost window over {@code area}, which never takes the focus. */
        static StandIn cover(Rectangle area, int color) throws InterruptedException {
            StandIn s = new StandIn("BotMaker live cover " + System.nanoTime(), false, color);
            s.start(WS_EX_TOPMOST | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, WS_POPUP | WS_VISIBLE,
                    area.x, area.y, area.width, area.height);
            Thread.sleep(300); // composed before anything captures or clicks through it
            return s;
        }

        /** A borderless window filling {@code screen}, as a fullscreen game's is; it takes no focus from the test. */
        static StandIn fullscreen(String title, Rectangle screen) throws InterruptedException {
            StandIn s = new StandIn(title, false, FILL);
            s.start(WS_EX_TOPMOST | WS_EX_NOACTIVATE, WS_POPUP | WS_VISIBLE,
                    screen.x, screen.y, screen.width, screen.height);
            return s;
        }

        /** An opaque box that clicks pass through, the way the SDK's run overlay draws one. */
        static StandIn overlayBox(Rectangle area, int color) throws InterruptedException {
            StandIn s = new StandIn("BotMaker live overlay " + System.nanoTime(), false, color);
            s.start(WS_EX_TOPMOST | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_LAYERED | WS_EX_TRANSPARENT,
                    WS_POPUP, area.x, area.y, area.width, area.height);
            // a layered window shows only once its alpha is set
            assertTrue(com.sun.jna.platform.win32.User32.INSTANCE.SetLayeredWindowAttributes(s.hwnd, 0, (byte) 255,
                    LWA_ALPHA), "the overlay box's alpha");
            User32.INSTANCE.ShowWindow(s.hwnd, SW_SHOWNOACTIVATE);
            Thread.sleep(300);
            return s;
        }

        private void start(int exStyle, int style, int x, int y, int width, int height) throws InterruptedException {
            CountDownLatch created = new CountDownLatch(1);
            loop = new Thread(() -> {
                com.sun.jna.platform.win32.User32 u = com.sun.jna.platform.win32.User32.INSTANCE;
                HINSTANCE module = Kernel32.INSTANCE.GetModuleHandle(null);
                String className = "BotMakerLive" + System.nanoTime();
                proc = this::procedure;
                WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
                wc.lpfnWndProc = proc;
                wc.hInstance = module;
                wc.lpszClassName = className;
                u.RegisterClassEx(wc);
                hwnd = u.CreateWindowEx(exStyle, className, title, style, x, y, width, height,
                        null, null, module, null);
                createError = Kernel32.INSTANCE.GetLastError(); // per thread: read here, not by the test
                created.countDown();
                WinUser.MSG msg = new WinUser.MSG();
                while (u.GetMessage(msg, null, 0, 0) > 0) {
                    u.TranslateMessage(msg);
                    u.DispatchMessage(msg);
                }
                u.UnregisterClass(className, module);
            }, "live-stand-in");
            loop.setDaemon(true);
            loop.start();
            assertTrue(created.await(5, TimeUnit.SECONDS), "the stand-in window was not created");
            assertNotNull(hwnd, "CreateWindowEx failed: " + createError);
            Thread.sleep(300);
        }

        private LRESULT procedure(HWND h, int message, WPARAM wParam, LPARAM lParam) {
            com.sun.jna.platform.win32.User32 u = com.sun.jna.platform.win32.User32.INSTANCE;
            boolean mouse = message >= 0x0200 && message <= 0x020E;
            boolean key = message >= 0x0100 && message <= 0x0109;
            if (mouse || key) {
                received.add(new Msg(message, wParam.longValue(), lParam.longValue()));
            }
            if (repaints && (message == User32.WM_LBUTTONDOWN || message == User32.WM_RBUTTONDOWN)) {
                clicked = !clicked;
                Win.INSTANCE.InvalidateRect(h, null, true);
            }
            if (key) {
                return new LRESULT(0); // no system menu loop on a posted Alt
            }
            switch (message) {
                case WM_ERASEBKGND -> {
                    RECT r = new RECT();
                    u.GetClientRect(h, r);
                    HBRUSH brush = Win.GDI.CreateSolidBrush(clicked ? FILL_CLICKED : color);
                    Win.INSTANCE.FillRect(new HDC(new Pointer(wParam.longValue())), r, brush);
                    com.sun.jna.platform.win32.GDI32.INSTANCE.DeleteObject(brush);
                    return new LRESULT(1);
                }
                case WM_DESTROY -> {
                    u.PostQuitMessage(0);
                    return new LRESULT(0);
                }
                default -> {
                    return u.DefWindowProc(h, message, wParam, lParam);
                }
            }
        }

        List<Msg> received() {
            return List.copyOf(received);
        }

        void clear() {
            received.clear();
        }

        /** Whether this window is what a real click at screen {@code p} would hit. */
        boolean isTopAt(Point p) {
            HWND there = User32.INSTANCE.WindowFromPoint(new POINT.ByValue(p.x, p.y));
            return there != null && hwnd.equals(User32.INSTANCE.GetAncestor(there, User32.GA_ROOT));
        }

        void resizeClient(int width, int height) {
            new WindowsController().resizeWindow(new GenericWindow(hwnd, title, null), width, height);
        }

        Msg await(String what, Predicate<Msg> match) {
            long until = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < until) {
                for (Msg m : received) {
                    if (match.test(m)) return m;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return fail("\"" + title + "\" never received " + what + "; it got " + received);
        }

        Msg awaitAt(int message, int x, int y) {
            return await(String.format("0x%04X at (%d,%d)", message, x, y),
                    m -> m.msg == message && m.at().equals(new Point(x, y)));
        }

        /** {@code text} as the window's {@code WM_CHAR}s, in order. */
        void awaitText(String text) {
            long until = System.currentTimeMillis() + 2000;
            String got = "";
            while (System.currentTimeMillis() < until) {
                List<Character> chars = new ArrayList<>();
                for (Msg m : received) {
                    if (m.msg == User32.WM_CHAR) chars.add((char) m.wParam);
                }
                StringBuilder b = new StringBuilder();
                chars.forEach(b::append);
                got = b.toString();
                if (got.equals(text)) return;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertEquals(text, got, "the characters \"" + title + "\" received");
        }

        /** Closes the window and waits until it is gone, so the next test doesn't find it still on top. */
        void close() {
            if (hwnd != null) {
                User32.INSTANCE.PostMessage(hwnd, WM_CLOSE, new WPARAM(0), new LPARAM(0));
            }
            try {
                loop.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The few calls the stand-in needs that neither {@link User32} nor JNA's platform binds. */
    interface Win extends StdCallLibrary {
        Win INSTANCE = Native.load("user32", Win.class);
        Gdi GDI = Native.load("gdi32", Gdi.class);

        int FillRect(HDC hdc, RECT rect, HBRUSH brush);

        boolean InvalidateRect(HWND hwnd, RECT rect, boolean erase);

        int GetDpiForWindow(HWND hwnd);

        boolean GetKeyboardLayoutNameW(char[] name);

        int GetWindowTextW(HWND hwnd, char[] text, int max);

        boolean SetWindowDisplayAffinity(HWND hwnd, int affinity);

        interface Gdi extends StdCallLibrary {
            HBRUSH CreateSolidBrush(int colorref);
        }
    }
}
