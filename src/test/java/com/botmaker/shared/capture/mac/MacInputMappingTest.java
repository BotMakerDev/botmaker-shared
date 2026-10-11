package com.botmaker.shared.capture.mac;

import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Mac controller's pure tables — key codes, buttons, window picking — on any OS, no native call. */
class MacInputMappingTest {

    @Test
    void keysymsReachTheirAnsiKeys() {
        assertEquals(0x00, MacKeyCodes.fromKeysym('a'));
        assertEquals(0x00, MacKeyCodes.fromKeysym('A'), "a letter is its key whatever the case");
        assertEquals(0x06, MacKeyCodes.fromKeysym('z'));
        assertEquals(0x1D, MacKeyCodes.fromKeysym('0'));
        assertEquals(0x19, MacKeyCodes.fromKeysym('9'));
        assertEquals(0x7A, MacKeyCodes.fromKeysym(0xFFBE), "F1");
        assertEquals(0x6F, MacKeyCodes.fromKeysym(0xFFC9), "F12");
        assertEquals(0x24, MacKeyCodes.fromKeysym(0xFF0D), "Return");
        assertEquals(0x33, MacKeyCodes.fromKeysym(0xFF08), "BackSpace is kVK_Delete");
        assertEquals(0x75, MacKeyCodes.fromKeysym(0xFFFF), "Delete is kVK_ForwardDelete");
        assertEquals(0x7B, MacKeyCodes.fromKeysym(0xFF51), "Left");
        assertEquals(0x4C, MacKeyCodes.fromKeysym(0xFF8D), "KP_Enter");
        assertEquals(0x52, MacKeyCodes.fromKeysym(0xFFB0), "KP_0");
    }

    @Test
    void altIsOptionAndMetaIsCommand() {
        assertEquals(0x3A, MacKeyCodes.fromKeysym(0xFFE9));
        assertEquals(0x37, MacKeyCodes.fromKeysym(0xFFEB));
        assertEquals(MacKeyCodes.FLAG_OPTION, MacKeyCodes.flagOf(0x3A));
        assertEquals(MacKeyCodes.FLAG_COMMAND, MacKeyCodes.flagOf(0x37));
        assertEquals(MacKeyCodes.FLAG_SHIFT, MacKeyCodes.flagOf(MacKeyCodes.fromKeysym(0xFFE1)));
        assertEquals(MacKeyCodes.FLAG_CONTROL, MacKeyCodes.flagOf(MacKeyCodes.fromKeysym(0xFFE3)));
        assertEquals(0, MacKeyCodes.flagOf(0x00), "a letter holds no modifier");
    }

    @Test
    void anUnknownKeysymHasNoKey() {
        assertEquals(MacKeyCodes.NONE, MacKeyCodes.fromKeysym(0x1234));
    }

    @Test
    void keysReadBackToTheKeysymsLinuxReports() {
        assertEquals('a', MacKeyCodes.toKeysym(0x00), "a letter is its lowercase keysym");
        assertEquals('z', MacKeyCodes.toKeysym(0x06));
        assertEquals(0xFFC9, MacKeyCodes.toKeysym(0x6F), "F12");
        assertEquals(0xFFD0, MacKeyCodes.toKeysym(0x50), "F19");
        assertEquals(0xFFEB, MacKeyCodes.toKeysym(0x37), "Command is Super, as Linux names that key");
        assertEquals(0xFFE9, MacKeyCodes.toKeysym(0x3A), "Option is Alt");
        assertEquals(0, MacKeyCodes.toKeysym(0x3F), "fn has no keysym");
        for (int key = 0; key < 0x80; key++) {
            long keysym = MacKeyCodes.toKeysym(key);
            if (keysym != 0) {
                assertEquals(key, MacKeyCodes.fromKeysym((int) keysym),
                        "round trip of key 0x" + Integer.toHexString(key));
            }
        }
    }

    @Test
    void buttonsMapToCoreGraphicsNumbersAndEvents() {
        assertEquals(0, MacInput.cgButton(1));
        assertEquals(2, MacInput.cgButton(2));
        assertEquals(1, MacInput.cgButton(3));
        assertEquals(CoreGraphics.kCGEventLeftMouseDown, MacInput.buttonEventType(1, true));
        assertEquals(CoreGraphics.kCGEventRightMouseUp, MacInput.buttonEventType(3, false));
        assertEquals(CoreGraphics.kCGEventOtherMouseDown, MacInput.buttonEventType(2, true));
    }

    @Test
    void aMoveWithAButtonDownIsThatButtonsDrag() {
        assertEquals(CoreGraphics.kCGEventMouseMoved, MacInput.moveEventType(0));
        assertEquals(CoreGraphics.kCGEventLeftMouseDragged, MacInput.moveEventType(1));
        assertEquals(CoreGraphics.kCGEventRightMouseDragged, MacInput.moveEventType(3));
        assertEquals(CoreGraphics.kCGEventOtherMouseDragged, MacInput.moveEventType(2));
    }

    @Test
    void halfPointStepsTurnAsFarLeftAsRight() {
        // On a 2× display a 1-pixel step is half a point: two of them must make one point either way.
        double carry = 0;
        long left = 0;
        long right = 0;
        double carryRight = 0;
        for (int i = 0; i < 4; i++) {
            MacInput.Split l = MacInput.Split.of(-0.5, carry);
            carry = l.carry();
            left += l.whole();
            MacInput.Split r = MacInput.Split.of(0.5, carryRight);
            carryRight = r.carry();
            right += r.whole();
        }
        assertEquals(-2, left);
        assertEquals(2, right);
    }

    @Test
    void theFrontMostWindowUnderThePointIsTheTarget() {
        MacWindow front = window(1, 0, 0, 400, 300);
        MacWindow back = window(2, 100, 100, 800, 600);
        List<MacWindow> frontToBack = List.of(front, back);
        assertEquals(1, MacWindow.topmostAt(frontToBack, 150, 150).orElseThrow().id());
        assertEquals(2, MacWindow.topmostAt(frontToBack, 600, 500).orElseThrow().id());
        assertTrue(MacWindow.topmostAt(frontToBack, 2000, 2000).isEmpty());
    }

    @Test
    void aWindowIsCalledByItsTitleElseItsApp() {
        assertEquals("Level 3", new MacWindow(1, 1, "Level 3", "Game", rect(), 0, 1).title());
        assertEquals("Game", new MacWindow(1, 1, "", "Game", rect(), 0, 1).title(),
                "blank, as every other app's title is before Screen Recording is granted");
    }

    @Test
    void onlyVisibleOrdinaryWindowsCount() {
        assertTrue(new MacWindow(1, 1, "a", "A", rect(), 0, 1).isOrdinary());
        assertFalse(new MacWindow(1, 1, "menu", "A", rect(), 25, 1).isOrdinary(), "a layer above windows");
        assertFalse(new MacWindow(1, 1, "a", "A", rect(), 0, 0).isOrdinary(), "invisible");
        assertFalse(new MacWindow(1, 1, "a", "A", new Rectangle2D.Double(0, 0, 1, 1), 0, 1).isOrdinary());
    }

    private static MacWindow window(long id, double x, double y, double w, double h) {
        return new MacWindow(id, 100 + (int) id, "w" + id, "App", new Rectangle2D.Double(x, y, w, h), 0, 1);
    }

    private static Rectangle2D rect() {
        return new Rectangle2D.Double(0, 0, 640, 480);
    }
}
