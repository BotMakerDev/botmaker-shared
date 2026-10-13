package com.botmaker.shared.launch;

import com.botmaker.shared.capture.GenericWindow;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The class names a window; its title only joins in when the class is shared by unrelated programs. */
class WindowIdentityTest {

    private static GenericWindow window(String title, String windowClass) {
        return new GenericWindow(new Object(), title, new Rectangle(0, 0, 800, 600), 1, windowClass);
    }

    @Test
    void aDistinctClassIsEnoughAndAGenericOneKeepsTheTitle() {
        assertEquals("firestone.exe", WindowIdentity.of(window("Firestone v1.2", "firestone.exe")).spec());
        assertEquals("steam_app_0|Firestone", WindowIdentity.of(window("Firestone", "steam_app_0")).spec());
        assertNull(WindowIdentity.of(window("", "steam_app_0")), "an untitled window of a generic class is nameless");
        assertNull(WindowIdentity.of(window("Firestone", null)));
    }

    @Test
    void theSpecReadsBack() {
        assertEquals(new WindowIdentity("steam_app_0", "Firestone"), WindowIdentity.parse(" steam_app_0|Firestone "));
        assertEquals(new WindowIdentity("firestone.exe", null), WindowIdentity.parse("firestone.exe"));
        assertNull(WindowIdentity.parse("|Firestone"));
        assertNull(WindowIdentity.parse(" "));
        assertNull(WindowIdentity.parse("steam_app_0"), "a generic class alone names every Proton game");
        assertNull(WindowIdentity.of("java", " "));
    }

    @Test
    void theSameWindowIgnoresCase() {
        assertTrue(WindowIdentity.parse("Firestone.exe").sameAs(WindowIdentity.parse("firestone.EXE")));
        assertFalse(WindowIdentity.parse("steam_app_0|Firestone").sameAs(WindowIdentity.parse("steam_app_0|Other")));
    }

    @Test
    void aMatchNeedsTheClassAndTheExactTitleWhenGiven() {
        WindowIdentity firestone = WindowIdentity.parse("steam_app_0|Firestone");
        assertTrue(firestone.matches(window("firestone", "STEAM_APP_0")));
        assertFalse(firestone.matches(window("Firestone - Discord", "steam_app_0")));
        assertFalse(firestone.matches(window("Firestone", "discord")));
        assertTrue(WindowIdentity.parse("firestone.exe").matches(window("anything", "firestone.exe")));
    }
}
