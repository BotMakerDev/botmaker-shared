package com.botmaker.shared.capture.linux.input;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link UinputBackend}'s keysym → evdev map against the active X layout.
 *
 * <p>uinput emits positions, not characters: evdev {@code KEY_A} (30) is the key where a US board has A. The
 * built-in table assumed that board, so on AZERTY a bot's {@code Key.A} typed {@code q} under uinput while the
 * XTest and Windows backends, which ask the layout, typed {@code a}. The map is now read off the X keyboard
 * mapping (X keycode = evdev code + 8), and the built-in table is only what a keysym the layout lacks falls
 * back to.
 */
class UinputLayoutTest {

    /** Keycodes 8..255, each row two levels wide (unshifted, shifted), all NoSymbol until bound. */
    private static final class Layout implements KeymapOps {
        private final Map<Integer, long[]> table = new HashMap<>();

        Layout bind(int evdev, long... syms) {
            long[] row = new long[2];
            System.arraycopy(syms, 0, row, 0, Math.min(syms.length, 2));
            table.put(evdev + 8, row);
            return this;
        }

        @Override public int minKeycode() { return 8; }
        @Override public int maxKeycode() { return 255; }
        @Override public int keysymsPerKeycode() { return 2; }
        @Override public long[] keysymsFor(int keycode) { return table.getOrDefault(keycode, new long[2]).clone(); }
        @Override public void rebind(int keycode, long[] keysyms) { throw new UnsupportedOperationException(); }
        @Override public void sync() { }
    }

    @Test
    void azertyLettersGoWhereTheLayoutPutsThem() {
        Layout azerty = new Layout()
            .bind(16, 'a', 'A')      // the key a US board calls Q
            .bind(30, 'q', 'Q')      // the key a US board calls A
            .bind(17, 'z', 'Z')
            .bind(44, 'w', 'W')
            .bind(39, 'm', 'M');
        Map<Integer, Integer> map = UinputBackend.keymapFor(azerty);

        assertEquals(16, map.get((int) 'a'));
        assertEquals(16, map.get((int) 'A'));
        assertEquals(30, map.get((int) 'q'));
        assertEquals(17, map.get((int) 'z'));
        assertEquals(44, map.get((int) 'w'));
        assertEquals(39, map.get((int) 'm'));
    }

    @Test
    void aUsLayoutChangesNothing() {
        Layout us = new Layout().bind(30, 'a', 'A').bind(2, '1', '!').bind(28, 0xFF0D);
        Map<Integer, Integer> map = UinputBackend.keymapFor(us);

        assertEquals(30, map.get((int) 'a'));
        assertEquals(2, map.get((int) '1'));
        assertEquals(28, map.get(0xFF0D));
    }

    /** AZERTY's digits are the shifted level of the top row; the position is still the "1" key. */
    @Test
    void aShiftedKeysymFindsItsKey() {
        Layout azerty = new Layout().bind(2, '&', '1').bind(3, 0xE9, '2');
        Map<Integer, Integer> map = UinputBackend.keymapFor(azerty);

        assertEquals(2, map.get((int) '1'));
        assertEquals(3, map.get((int) '2'));
    }

    /** A key holding the keysym unshifted wins over one that only reaches it with Shift. */
    @Test
    void theUnshiftedLevelWins() {
        Layout layout = new Layout().bind(12, '-', '_').bind(5, '4', '-');
        Map<Integer, Integer> map = UinputBackend.keymapFor(layout);

        assertEquals(12, map.get((int) '-'));
    }

    @Test
    void aKeysymTheLayoutLacksKeepsTheBuiltInCode() {
        Map<Integer, Integer> map = UinputBackend.keymapFor(new Layout());

        assertEquals(30, map.get((int) 'a'));
        assertEquals(88, map.get(0xFFC9));   // F12
        assertEquals(96, map.get(0xFF8D));   // KP_Enter
    }
}
