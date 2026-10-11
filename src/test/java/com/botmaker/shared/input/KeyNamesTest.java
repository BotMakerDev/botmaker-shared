package com.botmaker.shared.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Key names without libX11 match {@code XKeysymToString}'s, underscores read as spaces. */
class KeyNamesTest {

    @Test
    void functionAndEditingKeysHaveXsNames() {
        assertEquals("Pause", KeyNames.name(0xFF13));
        assertEquals("F1", KeyNames.name(0xFFBE));
        assertEquals("F12", KeyNames.name(0xFFC9));
        assertEquals("F19", KeyNames.name(0xFFD0));
        assertEquals("Scroll Lock", KeyNames.name(0xFF14));
        assertEquals("KP 7", KeyNames.name(0xFFB7));
        assertEquals("Super L", KeyNames.name(0xFFEB));
    }

    @Test
    void aCharacterIsNamedByItself() {
        assertEquals("a", KeyNames.name('a'));
        assertEquals("§", KeyNames.name(0xA7));
        assertEquals("ж", KeyNames.name(0x01000000L | 'ж'));
        assertEquals("space", KeyNames.name(0x20));
    }

    @Test
    void anUnknownKeysymHasNoName() {
        assertNull(KeyNames.name(0));
        assertNull(KeyNames.name(0xFE03));
        assertNull(KeyNames.name(0x7F));
    }
}
