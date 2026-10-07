package com.botmaker.shared.capture.windows;

import com.botmaker.shared.capture.windows.WindowMessages.ScanCode;
import com.sun.jna.Memory;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows input and capture arithmetic, checked on any OS: message encoding, scan-code flags, the capture
 * geometry and the ignored-click watch. None of it calls Win32; a wrong bit in any of it doesn't throw on
 * Windows either, the game just reads something else — which is why it is pinned here.
 */
class WindowsInputTest {

    // --- mouse messages ---

    @Test
    void eachButtonHasItsOwnMessagesAndFlag() {
        record Expect(int button, int down, int up, int flag) {}
        for (Expect e : List.of(
                new Expect(1, 0x0201, 0x0202, 0x0001),
                new Expect(2, 0x0207, 0x0208, 0x0010),
                new Expect(3, 0x0204, 0x0205, 0x0002),
                new Expect(8, 0x020B, 0x020C, 0x0020),
                new Expect(9, 0x020B, 0x020C, 0x0040))) {
            assertEquals(e.down(), WindowMessages.buttonMessage(e.button(), true), "down of " + e.button());
            assertEquals(e.up(), WindowMessages.buttonMessage(e.button(), false), "up of " + e.button());
            assertEquals(e.flag(), WindowMessages.buttonFlag(e.button()), "MK flag of " + e.button());
        }
    }

    @Test
    void aSideButtonNamesItselfInTheHighWord() {
        assertEquals((1L << 16) | 0x20, WindowMessages.buttonWParam(8, 0x20));
        assertEquals((2L << 16), WindowMessages.buttonWParam(9, 0), "a release reports the button no longer held");
        assertEquals(0x0011, WindowMessages.buttonWParam(1, 0x0011), "left with middle held: just the flags");
    }

    @Test
    void aPointLeftOfOrAboveTheWindowStaysNegative() {
        long lParam = WindowMessages.pointLParam(-5, -40);
        assertEquals(-5, (short) (lParam & 0xFFFF), "GET_X_LPARAM");
        assertEquals(-40, (short) ((lParam >> 16) & 0xFFFF), "GET_Y_LPARAM");
        assertEquals((411L << 16) | 937, WindowMessages.pointLParam(937, 411));
    }

    @Test
    void oneWheelNotchIsOneSignedDelta() {
        long up = WindowMessages.wheelWParam(true, User32.MK_LBUTTON);
        long down = WindowMessages.wheelWParam(false, 0);
        assertEquals(120, (short) ((up >> 16) & 0xFFFF));
        assertEquals(User32.MK_LBUTTON, up & 0xFFFF, "held buttons ride in the low word");
        assertEquals(-120, (short) ((down >> 16) & 0xFFFF));
    }

    // --- keys ---

    @Test
    void anExtendedKeyKeepsItsPrefix() {
        assertEquals(new ScanCode(0x4B, true), ScanCode.of(0xE04B), "left arrow, not numpad 4");
        assertEquals(new ScanCode(0x4B, false), ScanCode.of(0x004B), "numpad 4");
        assertEquals(new ScanCode(0x1D, true), ScanCode.of(0xE11D), "the E1 prefix (Pause) is extended too");
        assertTrue(ScanCode.of(0).isNone());
    }

    @Test
    void aKeystrokeCarriesItsScanCodeAndOnReleaseTheTransitionBits() {
        long down = WindowMessages.keyLParam(new ScanCode(0x1E, false), false, false);
        assertEquals(1, down & 0xFFFF, "repeat count");
        assertEquals(0x1E, (down >> 16) & 0xFF, "scan code");
        assertEquals(0, (down >> 24) & 1, "not extended");
        assertEquals(0, (down >> 29) & 1, "no Alt context");
        assertEquals(0, down >>> 30, "no previous state or transition on a press");

        long up = WindowMessages.keyLParam(new ScanCode(0x4B, true), true, true);
        assertEquals(1, (up >> 24) & 1, "extended");
        assertEquals(1, (up >> 29) & 1, "Alt context");
        assertEquals(3, (up >>> 30) & 3, "previous state and transition on a release");
    }

    @Test
    void altAndF10AndAltHeldKeysAreSystemKeys() {
        assertEquals(User32.WM_SYSKEYDOWN, WindowMessages.keyMessage(0x12, true, false), "Alt itself");
        assertEquals(User32.WM_SYSKEYUP, WindowMessages.keyMessage(0xA4, false, false), "left Alt");
        assertEquals(User32.WM_SYSKEYDOWN, WindowMessages.keyMessage(0x79, true, false), "F10");
        assertEquals(User32.WM_SYSKEYDOWN, WindowMessages.keyMessage(0x0D, true, true), "Enter with Alt held");
        assertEquals(User32.WM_KEYDOWN, WindowMessages.keyMessage(0x0D, true, false));
        assertEquals(User32.WM_KEYUP, WindowMessages.keyMessage(0x41, false, false));
    }

    @Test
    void aCharacterBehindAltGrGetsCtrlAndAlt() {
        assertEquals(List.of(0x11, 0x12), SendInputs.modifiers((short) 0x0630), "AZERTY @ is AltGr+0");
        assertEquals(List.of(0x10), SendInputs.modifiers((short) 0x0141), "A is Shift+a");
        assertEquals(List.of(), SendInputs.modifiers((short) 0x0041));
    }

    @Test
    void sendInputUsesTheScanCodeWheneverThereIsOne() {
        assertEquals(User32.KEYEVENTF_SCANCODE, SendInputs.keyFlags(new ScanCode(0x1E, false), true));
        assertEquals(User32.KEYEVENTF_SCANCODE | User32.KEYEVENTF_EXTENDEDKEY | User32.KEYEVENTF_KEYUP,
                SendInputs.keyFlags(new ScanCode(0x4B, true), false));
        assertEquals(User32.KEYEVENTF_KEYUP, SendInputs.keyFlags(ScanCode.NONE, false),
                "no scan code: by virtual key, which is the only thing left");
    }

    // --- capture geometry ---

    @Test
    void theClientAreaIsCutOutOfTheVisibleFrame() {
        Rectangle frame = new Rectangle(100, 50, 816, 639);       // visible bounds: title bar on top
        Rectangle client = new Rectangle(101, 81, 814, 607);
        assertEquals(new Rectangle(1, 31, 814, 607), WindowFrames.clientWithin(frame, client, 816, 639));
        assertEquals(new Rectangle(1, 31, 814, 600), WindowFrames.clientWithin(frame, client, 816, 631),
                "a frame shorter than the bounds is clipped, never read past");
        assertNull(WindowFrames.clientWithin(frame, new Rectangle(-32000, -32000, 0, 0), 816, 639),
                "a minimized window has no client area to cut");
    }

    @Test
    void blackIsSampledOnAGridNotAtRandom() {
        BufferedImage dark = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        assertTrue(WindowFrames.isAllBlack(dark));
        dark.setRGB(0, 0, 0x202020);
        assertFalse(WindowFrames.isAllBlack(dark), "the grid includes the corner");
        assertTrue(WindowFrames.isAllBlack(null));
    }

    @Test
    void aWindowCoversAScreenToWithinTwoPixels() {
        List<Rectangle> screens = List.of(new Rectangle(0, 0, 2560, 1440), new Rectangle(2560, 0, 1920, 1080));
        assertTrue(WindowFrames.coversAScreen(new Rectangle(2560, 0, 1920, 1080), screens));
        assertTrue(WindowFrames.coversAScreen(new Rectangle(1, 1, 2558, 1438), screens));
        assertFalse(WindowFrames.coversAScreen(new Rectangle(0, 0, 1920, 1080), screens),
                "the size of the second screen, on the first: not fullscreen there");
    }

    @Test
    void probesStayInsideTheRect() {
        Rectangle r = new Rectangle(-1920, 10, 7, 5);
        for (Point p : WindowFrames.probes(r)) {
            assertTrue(r.contains(p), p + " outside " + r);
        }
        assertEquals(new Point(960, 540), WindowFrames.probes(new Rectangle(0, 0, 1920, 1080)).getFirst());
    }

    // --- WGC plumbing that is plain data ---

    @Test
    void aSizeTravelsAsOneRegisterWidthLow() {
        long packed = WgcCapture.packSize(1280, 720);
        assertEquals(1280, (int) packed);
        assertEquals(720, (int) (packed >>> 32));
    }

    @Test
    void aStagingTextureKeepsTheSizeAndFormatAndDropsEverythingElse() {
        try (Memory desc = new Memory(WgcCapture.TextureDesc.SIZE)) {
            for (int off = 0; off < WgcCapture.TextureDesc.SIZE; off += 4) {
                desc.setInt(off, 0x7777);
            }
            desc.setInt(WgcCapture.TextureDesc.WIDTH, 1280);
            desc.setInt(WgcCapture.TextureDesc.HEIGHT, 720);
            desc.setInt(16, 87); // Format
            WgcCapture.TextureDesc.makeStaging(desc);
            assertEquals(1280, desc.getInt(0));
            assertEquals(720, desc.getInt(4));
            assertEquals(1, desc.getInt(8), "MipLevels");
            assertEquals(1, desc.getInt(12), "ArraySize");
            assertEquals(87, desc.getInt(16), "Format untouched");
            assertEquals(1, desc.getInt(20), "SampleDesc.Count");
            assertEquals(0, desc.getInt(24), "SampleDesc.Quality");
            assertEquals(3, desc.getInt(28), "D3D11_USAGE_STAGING");
            assertEquals(0, desc.getInt(32), "BindFlags");
            assertEquals(0x20000, desc.getInt(36), "D3D11_CPU_ACCESS_READ");
            assertEquals(0, desc.getInt(40), "MiscFlags");
        }
    }

    // --- the ignored-click watch ---

    /** A fake window whose frame is whatever the test says it is now, and a clock that runs "later" at once. */
    private static final class Fixture {
        final Map<String, BufferedImage> frames = new java.util.HashMap<>();
        final List<String> hints = new ArrayList<>();
        final AtomicInteger captures = new AtomicInteger();
        final IgnoredClickWatch<String> watch = new IgnoredClickWatch<>(w -> {
            captures.incrementAndGet();
            return frames.get(w);
        }, hints::add, (task, ms) -> task.run());

        void click(Runnable effect) {
            watch.around("game", "Game", effect);
        }
    }

    private static BufferedImage frame(int rgb) {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 40; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    @Test
    void threeClicksThatChangeNothingGiveTheHintOnce() {
        Fixture f = new Fixture();
        f.frames.put("game", frame(0x336699));
        for (int i = 0; i < 5; i++) {
            f.click(() -> {});
        }
        assertEquals(1, f.hints.size());
        assertTrue(f.hints.getFirst().contains("\"Game\"") && f.hints.getFirst().contains("Take over"),
                f.hints.getFirst());
        assertEquals(2 * IgnoredClickWatch.CLICKS, f.captures.get(), "nothing is captured once the watch is over");
    }

    @Test
    void oneClickThatChangesTheWindowEndsTheWatchWithoutAHint() {
        Fixture f = new Fixture();
        f.frames.put("game", frame(0x336699));
        f.click(() -> {});
        f.click(() -> f.frames.put("game", frame(0x99CC33)));
        f.click(() -> {});
        f.click(() -> {});
        assertTrue(f.hints.isEmpty());
        assertFalse(f.watch.watching());
    }

    @Test
    void noiseIsNotAChangeAndAResizeIs() {
        BufferedImage a = frame(0x336699);
        BufferedImage b = frame(0x346798);
        assertFalse(IgnoredClickWatch.changed(a, b), "a channel off by a few is compression, not a click");
        b.setRGB(20, 15, 0xFFFFFF);
        assertTrue(IgnoredClickWatch.changed(a, b), "one real pixel is a change: a pressed button can be small");
        assertTrue(IgnoredClickWatch.changed(a, new BufferedImage(41, 30, BufferedImage.TYPE_INT_RGB)));
        assertNull(IgnoredClickWatch.changed(a, null));
    }

    @Test
    void clicksFasterThanTheAnswersCostNoExtraCaptures() {
        Fixture f = new Fixture();
        f.frames.put("game", frame(0x336699));
        List<Runnable> answers = new ArrayList<>();
        IgnoredClickWatch<String> watch = new IgnoredClickWatch<>(w -> {
            f.captures.incrementAndGet();
            return f.frames.get(w);
        }, f.hints::add, (task, ms) -> answers.add(task));
        for (int i = 0; i < 7; i++) {
            watch.around("game", "Game", () -> {});
        }
        assertEquals(IgnoredClickWatch.CLICKS, f.captures.get(), "a before-capture for the first three only");
        answers.forEach(Runnable::run);
        assertEquals(1, f.hints.size());
    }

    @Test
    void aWindowThatCannotBeCapturedIsNotCounted() {
        Fixture f = new Fixture();
        for (int i = 0; i < 5; i++) {
            f.click(() -> {});
        }
        assertTrue(f.hints.isEmpty(), "no frame says nothing about the click");
        assertTrue(f.watch.watching());
    }
}
