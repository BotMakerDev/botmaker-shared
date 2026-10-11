package com.botmaker.shared.input.mac;

import com.botmaker.shared.capture.mac.CoreGraphics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the macOS listener names what it taps — keysyms, modifiers, buttons, wheel — on any OS, no native call. */
class MacInputListenerMappingTest {

    private static final int KVK_A = 0x00;
    private static final int KVK_F12 = 0x6F;
    private static final int KVK_RETURN = 0x24;
    private static final int KVK_LEFT_SHIFT = 0x38;
    private static final int KVK_RIGHT_SHIFT = 0x3C;
    private static final int KVK_COMMAND = 0x37;
    /** kVK_ISO_Section, the key left of 1 on an ISO keyboard: no keysym of its own. */
    private static final int KVK_SECTION = 0x0A;

    private static final long SHIFT = 0x20000;
    private static final long CAPS = 0x10000;
    private static final long OPTION = 0x80000;
    private static final long CONTROL = 0x40000;

    private static long keysym(int keyCode, long flags, String typed) {
        return MacInputListener.keysymOf(keyCode, flags, () -> typed);
    }

    @Test
    void aPrintableKeyIsTheCharacterItTyped() {
        assertEquals('a', keysym(KVK_A, 0, "a"));
        assertEquals('A', keysym(KVK_A, SHIFT, "A"), "Shift held, as XRecord's shift level");
        assertEquals('q', keysym(KVK_A, 0, "q"), "an AZERTY layout types q on the A key");
        assertEquals(0xA7, keysym(KVK_SECTION, 0, "§"), "Latin-1 is its code point");
        assertEquals(0x01000000L | 'ж', keysym(KVK_A, 0, "ж"), "X's Unicode keysym");
    }

    @Test
    void capsLockLeavesTheCaseToShiftAsXDoes() {
        assertEquals('a', keysym(KVK_A, CAPS, "A"));
        assertEquals('A', keysym(KVK_A, CAPS | SHIFT, "A"));
    }

    @Test
    void aChordReadsTheKeyNotItsThirdLevel() {
        assertEquals('a', keysym(KVK_A, OPTION, "å"), "Option+A types å, which X never reads");
        assertEquals('A', keysym(KVK_A, OPTION | SHIFT, "Å"));
        assertEquals('a', keysym(KVK_A, CONTROL, "\u0001"), "Control+A types ^A");
    }

    @Test
    void aFunctionKeyIsItsOwnKeysymAndAsksNothing() {
        assertEquals(0xFFC9, MacInputListener.keysymOf(KVK_F12, 0, () -> {
            throw new AssertionError("F12 must not read the typed characters");
        }));
        assertEquals(0xFF0D, keysym(KVK_RETURN, 0, "\r"));
    }

    @Test
    void nothingPrintableFallsBackToTheKey() {
        assertEquals('a', keysym(KVK_A, 0, ""));
        assertEquals(0, keysym(KVK_SECTION, 0, ""), "no character and no keysym: NoSymbol");
    }

    @Test
    void eachShiftIsReadOffItsOwnSide() {
        long leftAndRight = SHIFT | 0x2 | 0x4 | 0x100;
        long leftOnly = SHIFT | 0x2 | 0x100;
        assertTrue(MacInputListener.modifierDown(KVK_RIGHT_SHIFT, leftAndRight));
        assertFalse(MacInputListener.modifierDown(KVK_RIGHT_SHIFT, leftOnly),
                "right Shift let go while left is held: a release, though the Shift flag stays");
        assertTrue(MacInputListener.modifierDown(KVK_LEFT_SHIFT, leftOnly));
    }

    @Test
    void aSyntheticModifierFallsBackToThePlainFlag() {
        assertTrue(MacInputListener.modifierDown(KVK_LEFT_SHIFT, SHIFT));
        assertFalse(MacInputListener.modifierDown(KVK_LEFT_SHIFT, 0x100000), "Command held, Shift not");
        assertTrue(MacInputListener.modifierDown(KVK_COMMAND, 0x100000));
        assertFalse(MacInputListener.modifierDown(KVK_A, SHIFT), "not a modifier");
    }

    @Test
    void buttonsComeOutWithXNumbers() {
        assertEquals(1, MacInputListener.xButton(CoreGraphics.kCGEventLeftMouseDown, 0));
        assertEquals(3, MacInputListener.xButton(CoreGraphics.kCGEventRightMouseUp, 1));
        assertEquals(2, MacInputListener.xButton(CoreGraphics.kCGEventOtherMouseDown, 2));
        assertEquals(8, MacInputListener.xButton(CoreGraphics.kCGEventOtherMouseDown, 3), "back");
        assertEquals(9, MacInputListener.xButton(CoreGraphics.kCGEventOtherMouseUp, 4), "forward");
        assertEquals(0, MacInputListener.xButton(CoreGraphics.kCGEventOtherMouseUp, 7));
        assertEquals(0, MacInputListener.xButton(CoreGraphics.kCGEventKeyDown, 0));
        assertTrue(MacInputListener.isPress(CoreGraphics.kCGEventOtherMouseDown));
        assertFalse(MacInputListener.isPress(CoreGraphics.kCGEventLeftMouseUp));
    }

    @Test
    void aMouseWheelClicksOncePerLine() {
        assertEquals(3, MacInputListener.Wheel.lines(3).clicks());
        assertEquals(-1, MacInputListener.Wheel.lines(-1).clicks());
        assertEquals(10, MacInputListener.Wheel.lines(400).clicks(), "a flick is cut");
    }

    @Test
    void trackpadStepsAddUpToClicksBothWays() {
        int up = 0;
        int down = 0;
        double carryUp = 0;
        double carryDown = 0;
        for (int i = 0; i < 10; i++) {
            MacInputListener.Wheel u = MacInputListener.Wheel.of(3, carryUp);
            carryUp = u.carry();
            up += u.clicks();
            MacInputListener.Wheel d = MacInputListener.Wheel.of(-3, carryDown);
            carryDown = d.carry();
            down += d.clicks();
        }
        assertEquals(3, up, "30 points, one click per " + MacInputListener.POINTS_PER_CLICK);
        assertEquals(-3, down);
    }
}
