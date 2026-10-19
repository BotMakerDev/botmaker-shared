package com.botmaker.shared.game;

import com.botmaker.shared.launch.SteamIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Steam's non-Steam shortcuts, Ubisoft Connect, Battle.net and itch (2026-10-19). */
class MoreLibrariesTest {

    @Test
    void steamShortcutsAreReadFromTheBinaryVdf(@TempDir Path config) throws IOException {
        Files.createDirectories(config.resolve("grid"));
        Path grid = Files.writeString(config.resolve("grid").resolve(Integer.toUnsignedString(0x9ABCDEF0) + "p.png"), "");
        ByteArrayOutputStream vdf = new ByteArrayOutputStream();
        map(vdf, "shortcuts");
        map(vdf, "0");
        int32(vdf, "appid", 0x9ABCDEF0);
        string(vdf, "AppName", "Celeste");
        string(vdf, "Exe", "\"/games/celeste/Celeste\"");
        float32(vdf, "LastPlayTime");
        map(vdf, "tags");
        string(vdf, "0", "favorite");
        vdf.write(0x08);
        vdf.write(0x08);
        map(vdf, "1");
        string(vdf, "appname", "Old Shortcut");
        string(vdf, "exe", "\"C:\\old.exe\"");
        vdf.write(0x08);
        vdf.write(0x08);
        vdf.write(0x08);

        List<InstalledGame> games = SteamShortcuts.parse(vdf.toByteArray(), config);
        assertEquals(new InstalledGame("steam", SteamIds.shortcutGameId(0x9ABCDEF0), "Celeste", grid), games.get(0));
        int legacy = SteamIds.legacyShortcutAppId("\"C:\\old.exe\"", "Old Shortcut");
        assertEquals(SteamIds.shortcutGameId(legacy), games.get(1).id(), "no appid: Steam's CRC of program and name");
        assertNull(games.get(1).artwork());

        byte[] cut = java.util.Arrays.copyOf(vdf.toByteArray(), 40);
        assertTrue(SteamShortcuts.parse(cut, config).isEmpty(), "a truncated file is no crash");
    }

    @Test
    void ubisoftGamesAreLinksNamedByTheirUninstallEntry() {
        assertEquals(Optional.of(new InstalledGame("url", "uplay://launch/4553/0", "Anno 1800", null)),
                UbisoftLibraryScanner.game("4553", "C:/Program Files (x86)/Ubisoft/Anno 1800/", "Anno 1800"));
        assertEquals("Far Cry 5", UbisoftLibraryScanner.game("1803", "D:\\Games\\Far Cry 5\\", null)
                .orElseThrow().name(), "no uninstall entry: its folder");
        assertTrue(UbisoftLibraryScanner.game("1803", "", "X").isEmpty(), "not installed");
        assertTrue(UbisoftLibraryScanner.game("../x", "C:/x", "X").isEmpty());
    }

    @Test
    void battleNetGamesAreLinksByTheirProductCode() {
        assertEquals(Optional.of(new InstalledGame("url", "battlenet://Pro", "Overwatch", null)),
                BattleNetLibraryScanner.game(Map.of("DisplayName", "Overwatch", "UninstallString",
                        "\"C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe\" --lang=enUS "
                                + "--uid=prometheus --displayname=\"Overwatch\"")));
        assertTrue(BattleNetLibraryScanner.game(Map.of("DisplayName", "New Game", "UninstallString",
                "\"C:\\ProgramData\\Battle.net\\Agent\\Blizzard Uninstaller.exe\" --uid=unknown_game")).isEmpty(),
                "an unknown product is left to the Start menu");
        assertTrue(BattleNetLibraryScanner.game(Map.of("DisplayName", "Notepad++", "UninstallString",
                "C:\\Program Files\\Notepad++\\uninstall.exe")).isEmpty());
    }

    @Test
    void itchGamesAreTheirFoldersLargestProgram(@TempDir Path apps) throws IOException {
        Path game = Files.createDirectories(apps.resolve("dino-run"));
        Files.write(game.resolve("DinoRun.exe"), new byte[2048]);
        Files.write(game.resolve("UnityCrashHandler64.exe"), new byte[4096]);
        Files.createDirectories(game.resolve(".itch"));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(game.resolve(".itch/receipt.json.gz")))) {
            out.write("{\"game\":{\"id\":1,\"title\":\"Dino Run\"},\"files\":[]}".getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(apps.resolve("no-receipt"));
        Files.write(apps.resolve("no-receipt").resolve("x.exe"), new byte[10]);

        List<InstalledGame> games = ItchLibraryScanner.gamesIn(apps);
        assertEquals(1, games.size());
        assertEquals("Dino Run", games.get(0).name());
        assertEquals(game.resolve("DinoRun.exe").toString(), games.get(0).id(), "the crash handler is set aside");
    }

    private static void map(ByteArrayOutputStream out, String key) {
        out.write(0x00);
        cString(out, key);
    }

    private static void string(ByteArrayOutputStream out, String key, String value) {
        out.write(0x01);
        cString(out, key);
        cString(out, value);
    }

    private static void int32(ByteArrayOutputStream out, String key, int value) {
        out.write(0x02);
        cString(out, key);
        for (int i = 0; i < 4; i++) out.write((value >>> (8 * i)) & 0xFF);
    }

    private static void float32(ByteArrayOutputStream out, String key) {
        out.write(0x03);
        cString(out, key);
        out.writeBytes(new byte[4]);
    }

    private static void cString(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }
}
