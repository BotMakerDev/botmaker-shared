package com.botmaker.shared.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Steam tool filter, the GOG registry reader and the Faugus entry writer. */
class NewLibrariesTest {

    @Test
    void steamsOwnToolsAreNotGames() {
        assertTrue(SteamLibraryScanner.isTool("1493710", "Proton Experimental"));
        assertTrue(SteamLibraryScanner.isTool("9999999", "Proton 11.0"), "a Proton newer than the id list");
        assertTrue(SteamLibraryScanner.isTool("9999998", "Steam Linux Runtime 4.0"));
        assertTrue(SteamLibraryScanner.isTool("228980", "Steamworks Common Redistributables"));
        assertFalse(SteamLibraryScanner.isTool("2379780", "Balatro"));
        assertFalse(SteamLibraryScanner.isTool("1", "Protonaut"), "a game whose name only starts with Proton");
    }

    @Test
    void gogGamesAreReadFromTheRegistryAsExeTargets() {
        List<InstalledGame> games = GogLibraryScanner.parse("""

                HKEY_LOCAL_MACHINE\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1207658924
                    gameID    REG_SZ    1207658924
                    gameName    REG_SZ    Unreal Tournament
                    exe    REG_SZ    C:\\GOG Games\\UT\\System\\UnrealTournament.exe

                HKEY_LOCAL_MACHINE\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1
                    gameName    REG_SZ    No Exe

                HKEY_LOCAL_MACHINE\\SOFTWARE\\WOW6432Node\\GOG.com\\Games\\1207666893
                    gameName    REG_SZ    Gwent
                    exe    REG_SZ    D:\\Games\\Gwent\\Gwent.exe
                """);
        assertEquals(List.of("Gwent", "Unreal Tournament"), games.stream().map(InstalledGame::name).toList());
        assertEquals("C:\\GOG Games\\UT\\System\\UnrealTournament.exe", games.get(1).id());
        assertEquals("exe", games.get(1).platform());
    }

    @Test
    void aFaugusIdIsFaugusOwnSpellingMadeUnique() {
        var games = new ObjectMapper().createArrayNode();
        games.addObject().put("gameid", "my-game");
        assertEquals("my-game-2", FaugusEntries.uniqueId(games, "My Game!"));
        assertEquals("diablo-iv", FaugusEntries.uniqueId(games, "Diablo IV"));
        assertEquals("game", FaugusEntries.uniqueId(games, "★"));
    }

    @Test
    void aFaugusEntryUsesFaugusOwnDefaults() {
        ObjectNode entry = FaugusEntries.entry("tool", "Tool", Path.of("/home/u/Downloads/tool.exe"),
                new FaugusEntries.Defaults(Path.of("/home/u/Faugus"), "Proton-CachyOS Latest"));
        assertEquals("/home/u/Faugus/tool", entry.path("prefix").asText());
        assertEquals("/home/u/Downloads/tool.exe", entry.path("path").asText());
        assertEquals("Proton-CachyOS Latest", entry.path("runner").asText());
        assertFalse(entry.path("hidden").asBoolean(true));
    }
}
