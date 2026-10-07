package com.botmaker.shared.vnc;

import org.junit.jupiter.api.Test;

import static com.botmaker.shared.vnc.Keysyms.NativeKeys.KEYSYM;
import static com.botmaker.shared.vnc.Keysyms.NativeKeys.VIRTUAL_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Windows virtual keys and typed characters as the X keysyms VNC sends. */
class KeysymsTest {

    @Test
    void aVirtualKeyIsThePhysicalKeysKeysym() {
        assertEquals('q', Keysyms.of('Q', VIRTUAL_KEY), "a letter key, unshifted");
        assertEquals('7', Keysyms.of('7', VIRTUAL_KEY));
        assertEquals(0xFFBE, Keysyms.of(0x70, VIRTUAL_KEY), "F1");
        assertEquals(0xFFC9, Keysyms.of(0x7B, VIRTUAL_KEY), "F12");
        assertEquals(0xFFB5, Keysyms.of(0x65, VIRTUAL_KEY), "numpad 5");
        assertEquals(0xFF0D, Keysyms.of(0x0D, VIRTUAL_KEY), "enter");
        assertEquals(0xFFE3, Keysyms.of(0x11, VIRTUAL_KEY), "ctrl");
        assertEquals(0xFF52, Keysyms.of(0x26, VIRTUAL_KEY), "up");
        assertEquals(';', Keysyms.of(0xBA, VIRTUAL_KEY), "the US ; key");
        assertEquals(0, Keysyms.of(0xFF, VIRTUAL_KEY), "no keysym for an unknown key");
        assertEquals(0xFF0D, Keysyms.of(0xFF0D, KEYSYM), "a Linux code is already a keysym");
    }

    @Test
    void aCharacterIsLatin1OrUnicodeKeysym() {
        assertEquals('A', Keysyms.ofChar('A'));
        assertEquals(0xE9, Keysyms.ofChar('é'));
        assertEquals(0x010020AC, Keysyms.ofChar('€'));
        assertEquals(0xFF0D, Keysyms.ofChar('\n'));
        assertEquals(0xFF09, Keysyms.ofChar('\t'));
        assertEquals(0xFF1B, Keysyms.ofChar(0x1B), "escape");
        assertEquals(0xFFFF, Keysyms.ofChar(0x7F), "delete");
        assertEquals(0, Keysyms.ofChar(0x07), "a bell types nothing");
    }

    @Test
    void capitalsAndShiftedSymbolsNeedShift() {
        assertTrue(Keysyms.needsShift('Q'));
        assertTrue(Keysyms.needsShift('?'));
        assertFalse(Keysyms.needsShift('q'));
        assertFalse(Keysyms.needsShift('/'));
        assertFalse(Keysyms.needsShift('é'));
    }
}
