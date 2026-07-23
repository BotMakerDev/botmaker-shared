package com.botmaker.shared.capture.linux.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link UinputBackend}'s keysym → evdev coverage.
 *
 * <p>The keymap once covered only letters, digits and a handful of control keys; every other keysym missed it
 * and {@code key()} returned without emitting anything, so CTRL, ALT, DELETE, the arrows and F1–F12 did
 * nothing at all with no error. Nothing failed — which is why it survived. These constants mirror the SDK's
 * {@code Key} enum (shared cannot import it), so a key added there needs a line here and a line in the map.
 */
class UinputKeymapTest {

	private static void assertMapped(String name, int keysym) {
		assertTrue(UinputBackend.mapsKeysym(keysym),
			() -> String.format("keysym 0x%04X (%s) has no evdev code — uinput would silently drop it", keysym, name));
	}

	@Test
	void mapsEveryLetterAndDigit() {
		for (char c = 'a'; c <= 'z'; c++) {
			assertMapped(String.valueOf(c), c);
			assertMapped(String.valueOf(Character.toUpperCase(c)), Character.toUpperCase(c));
		}
		for (char c = '0'; c <= '9'; c++) {
			assertMapped(String.valueOf(c), c);
		}
	}

	@Test
	void mapsModifiersInBothVariants() {
		assertMapped("Shift_L", 0xFFE1);
		assertMapped("Shift_R", 0xFFE2);
		assertMapped("Control_L", 0xFFE3);
		assertMapped("Control_R", 0xFFE4);
		assertMapped("Alt_L", 0xFFE9);
		assertMapped("Alt_R", 0xFFEA);
		assertMapped("Super_L", 0xFFEB);
		assertMapped("Super_R", 0xFFEC);
	}

	@Test
	void mapsArrowsAndFunctionKeys() {
		assertMapped("Left", 0xFF51);
		assertMapped("Up", 0xFF52);
		assertMapped("Right", 0xFF53);
		assertMapped("Down", 0xFF54);
		for (int i = 0; i < 12; i++) {
			assertMapped("F" + (i + 1), 0xFFBE + i);
		}
	}

	@Test
	void mapsCommonControls() {
		assertMapped("Return", 0xFF0D);
		assertMapped("KP_Enter", 0xFF8D);
		assertMapped("Escape", 0xFF1B);
		assertMapped("space", ' ');
		assertMapped("Tab", 0xFF09);
		assertMapped("BackSpace", 0xFF08);
		assertMapped("Delete", 0xFFFF);
	}
}
