// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.launch;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.launch.GameMarker.Key;
import com.botmaker.shared.platform.Os;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** What a launched game's process carries to name its game, per launcher. */
class GameMarkerTest {

    private static final String FAUGUS_GAMES = """
            [{"gameid": "project-igi", "runner": "Default", "launch_arguments": ""},
             {"gameid": "battlenet", "runner": "Proton-CachyOS Latest",
              "launch_arguments": "WINE_SIMULATE_WRITECOPY=1\\nGAMEID=umu-2379780"},
             {"gameid": "through-steam", "runner": "Steam", "launch_arguments": ""}]
            """;

    @Test
    void aSteamGameIsNamedByItsAppId() {
        LaunchSpec balatro = LaunchSpec.parse("steam:2379780");
        assertEquals(new GameMarker(Key.STEAM_APP_ID, "2379780"), GameMarker.of(balatro));
        assertEquals("steam_app_2379780", GameMarker.windowClass(balatro));
    }

    /** Faugus names every game it starts by its id, whatever the runner. */
    @Test
    void aFaugusGameIsNamedByItsId() {
        assertEquals(new GameMarker(Key.FAUGUS_ID, "project-igi"),
                GameMarker.of(LaunchSpec.parse("faugus:project-igi")));
    }

    /** Proton names the window by umu's id: GAMEID without its {@code umu-}, by default a hash of Faugus's id. */
    @Test
    void aFaugusGamesClassIsItsUmuId() {
        assertEquals("faugus" + String.format("%08x", crc32("project-igi")),
                GameMarker.faugusAppId(FAUGUS_GAMES, "project-igi"));
        assertEquals("2379780", GameMarker.faugusAppId(FAUGUS_GAMES, "battlenet"), "a GAMEID the entry sets wins");
        assertNull(GameMarker.faugusAppId(FAUGUS_GAMES, "through-steam"), "Faugus's Steam runner uses no umu");
        assertNull(GameMarker.faugusAppId(FAUGUS_GAMES, "gone"));
        assertNull(GameMarker.faugusAppId("not json", "project-igi"));
    }

    @Test
    void aLutrisGameIsNamedByItsName() {
        assertEquals(new GameMarker(Key.GAME_NAME, "Ubisoft Connect"), GameMarker.lutris("Ubisoft Connect"));
        assertNull(GameMarker.lutris(" "));
    }

    @Test
    void onlySteamAndFaugusNameAClass() {
        assertNull(GameMarker.windowClass(LaunchSpec.parse("lutris:3")));
        assertNull(GameMarker.windowClass(LaunchSpec.parse("exe:/games/celeste/Celeste")));
        assertNull(GameMarker.windowClass(null));
        assertNull(GameMarker.of(null));
    }

    @Test
    void theEnvironmentNamesTheGameExactly() {
        GameMarker marker = new GameMarker(Key.STEAM_APP_ID, "2379780");
        assertTrue(marker.in(new String[] {"HOME=/home/x", "SteamAppId=2379780"}));
        assertFalse(marker.in(new String[] {"SteamAppId=23797801"}));
        assertFalse(marker.in(new String[] {"XSteamAppId=2379780"}));
        assertFalse(marker.in(new String[0]));
    }

    @Test
    void anUnreadableProcessIsNotMarked() {
        assertFalse(new GameMarker(Key.STEAM_APP_ID, "2379780").marks(-1));
    }

    /** What a launch starts carries its own marker, and nothing started outside it does. */
    @Test
    void aLaunchMarksWhatItStarts() throws Exception {
        assumeTrue(Os.current() == Os.LINUX, "a process's environment is read on Linux only");
        GameMarker launch = GameMarker.launch();
        assertEquals(Key.BOTMAKER_LAUNCH, launch.key());
        assertNotEquals(launch, GameMarker.launch(), "each launch is its own");
        Process marked = launch.during(() -> start("sleep", "30"));
        Process plain = start("sleep", "30");
        try {
            assertTrue(launch.marks(marked.pid()));
            assertFalse(launch.marks(plain.pid()));
        } finally {
            marked.destroyForcibly();
            plain.destroyForcibly();
        }
    }

    private static Process start(String... command) {
        try {
            return Spawn.detached(command);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
