package com.botmaker.shared.game;

import com.botmaker.shared.launch.LutrisLibrary;
import com.botmaker.shared.platform.Os;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lutris as Linux's one library (2026-10-29): what the picker lists there, and how a game Lutris runs through
 * Steam becomes the portable {@code steam:<appid>} a bot written on Windows lists.
 */
class LutrisPickerTest {

    @Test
    void linuxPicksFromLutrisAloneAndElsewhereFromWhatRunsThere() {
        assertEquals(List.of(LutrisLibraryScanner.PLATFORM),
                GameLibraries.pickable(Os.LINUX).stream().map(GameLibraryProvider::platform).toList());
        List<String> windows = GameLibraries.pickable(Os.WINDOWS).stream().map(GameLibraryProvider::platform).toList();
        assertTrue(windows.contains(SteamLibraryScanner.PLATFORM) && windows.contains(EpicLibraryScanner.PLATFORM),
                windows.toString());
        assertFalse(windows.contains(LutrisLibraryScanner.PLATFORM), windows.toString());
    }

    @Test
    void aGameLutrisRunsThroughSteamIsListedAsTheSteamGame() {
        Map<String, String> steam = LutrisLibraryScanner.steamIdsByTitle(List.of(
                new InstalledGame("steam", "1013320", "Firestone: Idle RPG", null),
                new InstalledGame("steam", "2379780", "Balatro", null),
                // Lutris's "Create Steam shortcut": a 64-bit game id, no store game — it must not collide.
                new InstalledGame("steam", "16245283705034784768", "Balatro", null)));

        InstalledGame balatro = LutrisLibraryScanner.listed(new LutrisLibrary.Game("14", "Balatro", null, "steam"),
                steam, Set.of());
        assertEquals("steam", balatro.platform());
        assertEquals("2379780", balatro.id());

        // A title Steam spells otherwise is no match: listed as Lutris's, which launches it all the same.
        InstalledGame firestone = LutrisLibraryScanner.listed(
                new LutrisLibrary.Game("8", "Firestone – Idle Clicker Online RPG", null, "steam"), steam, Set.of());
        assertEquals(LutrisLibraryScanner.PLATFORM, firestone.platform());
        assertEquals("8", firestone.id());

        InstalledGame wine = LutrisLibraryScanner.listed(new LutrisLibrary.Game("2", "Balatro", null, "wine"), steam,
                Set.of());
        assertEquals(LutrisLibraryScanner.PLATFORM, wine.platform(), "only Lutris's Steam runner is Steam's game");
    }

    @Test
    void aTitleMatchStandsOnlyWhereLutrisHasASteamConfigOfThatAppId() {
        Map<String, String> steam = Map.of(LutrisLibraryScanner.title("Balatro"), "2379780");
        LutrisLibrary.Game balatro = new LutrisLibrary.Game("14", "Balatro", null, "steam");
        assertEquals(Optional.of("2379780"), LutrisLibraryScanner.steamAppId(balatro, steam, Set.of("2379780", "11020")));
        // Lutris's configs name other app ids: the entry is another game titled alike, so it is not Steam's.
        assertEquals(Optional.empty(), LutrisLibraryScanner.steamAppId(balatro, steam, Set.of("11020")));
        assertEquals(Optional.of("2379780"), LutrisLibraryScanner.steamAppId(balatro, steam, Set.of()),
                "configs unreadable: the title alone");
    }

    @Test
    void aTitleIsComparedWithoutCasePunctuationOrSpacing() {
        assertEquals(LutrisLibraryScanner.title("Hollow Knight: Silksong"), LutrisLibraryScanner.title("hollow knight silksong"));
        Map<String, String> twice = LutrisLibraryScanner.steamIdsByTitle(List.of(
                new InstalledGame("steam", "1", "Doom", null), new InstalledGame("steam", "2", "DOOM", null)));
        assertTrue(twice.isEmpty(), "a title two games share maps to neither");
    }
}
