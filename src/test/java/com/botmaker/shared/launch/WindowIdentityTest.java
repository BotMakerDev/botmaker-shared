// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

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

    /**
     * The live check of 2026-11-25: Proton's window before the game names it is {@code steam_proton}, titled
     * {@code steam_proton}, and that class alone matched Honkai: Star Rail open beside it.
     */
    @Test
    void protonsOwnWindowIsGenericAndItsPlaceholderNamesNoGame() {
        assertTrue(WindowIdentity.isGeneric("steam_proton"));
        assertNull(WindowIdentity.of(window("steam_proton", "steam_proton")), "titled with its own class: no name yet");
        assertNull(WindowIdentity.of(window(" STEAM_PROTON ", "steam_proton")));
        assertEquals("steam_proton|Balatro", WindowIdentity.of(window("Balatro", "steam_proton")).spec());
        assertFalse(WindowIdentity.of(window("Balatro", "steam_proton"))
                .matches(window("Honkai: Star Rail", "steam_proton")));
        assertTrue(WindowIdentity.isPlaceholder(window("", "game.exe")));
        assertFalse(WindowIdentity.isPlaceholder(window("Game", "game.exe")));
    }

    /** A title that counts frames or time is one game still: what a pick waits on to settle. */
    @Test
    void theSameGameIgnoresTheTitlesNumbers() {
        assertTrue(WindowIdentity.parse("wine|Game - 60 fps").sameGame(WindowIdentity.parse("WINE|Game -  144 fps")));
        assertFalse(WindowIdentity.parse("wine|Game").sameGame(WindowIdentity.parse("wine|Other")));
        assertFalse(WindowIdentity.parse("wine|Game").sameGame(WindowIdentity.parse("java|Game")));
        assertTrue(WindowIdentity.parse("game.exe").sameGame(WindowIdentity.parse("Game.EXE")));
        assertTrue(WindowIdentity.parse("wine|Game 59.9 fps").sameGame(WindowIdentity.parse("wine|Game 60 fps")));
    }

    /** A pick saved while its title counted 60 frames finds the window counting 144 on the next run. */
    @Test
    void aSavedTitleMatchesWhateverItsNumbersSayNow() {
        assertTrue(WindowIdentity.parse("wine|Game - 60 fps").matches(window("Game - 144 fps", "wine")));
        assertFalse(WindowIdentity.parse("wine|Game - 60 fps").matches(window("Other - 60 fps", "wine")));
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
