package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LutrisLibraryTest {

    @Test
    void theJsonListIsReadPastLutrisOwnLogLines() {
        Map<String, LutrisLibrary.Game> games = LutrisLibrary.parse("""
                2026-10-07 11:00:00,000: Startup info
                WARNING: [Loader Message] Code 0 : ICD for selected physical device does not export vkGet!
                [
                  {"id": 3, "slug": "epic-games-store", "name": "Epic Games Store", "runner": "wine",
                   "coverPath": "/nowhere/epic-games-store.jpg"},
                  {"id": 1, "slug": "osu", "name": "osu!", "coverPath": null},
                  {"id": 8, "slug": "firestone", "name": "Firestone", "runner": "steam"}
                ]
                """);
        assertEquals(List.of("3", "1", "8"), List.copyOf(games.keySet()), "Lutris's own order");
        assertEquals("Epic Games Store", games.get("3").name());
        assertNull(games.get("3").cover(), "a cover file that doesn't exist is no cover");
        assertFalse(games.get("3").throughSteam());
        assertFalse(games.get("1").throughSteam(), "no runner is not Steam");
        assertTrue(games.get("8").throughSteam(), "a Steam-runner game is started by Steam");
    }

    @Test
    void anythingUnreadableIsNoGames() {
        assertEquals(Map.of(), LutrisLibrary.parse(null));
        assertEquals(Map.of(), LutrisLibrary.parse("lutris: command failed"));
        assertEquals(Map.of(), LutrisLibrary.parse("[ not json"));
    }

    @Test
    void bothNewKindsHaveAChildLadder() {
        assertEquals(List.of(List.of("lutris", "lutris:rungameid/3"),
                        List.of("flatpak", "run", "net.lutris.Lutris", "lutris:rungameid/3")),
                LaunchCommands.childLadder(LaunchSpec.parse("lutris:3")));
        assertEquals(List.of(List.of("gtk-launch", "org.kde.kpat")),
                LaunchCommands.childLadder(LaunchSpec.parse("desktop:org.kde.kpat")));
    }

    @Test
    void theListHoldsWhileTheLibraryFileIsUnchanged() {
        assertTrue(LutrisLibrary.fresh(500, 500, 0, 3_600_000), "an hour later, same pga.db: no lutris -l");
        assertFalse(LutrisLibrary.fresh(500, 900, 1_000, 1_001), "a game added or removed reads it again");
        assertTrue(LutrisLibrary.fresh(-1, -1, 1_000, 30_000), "no pga.db found: a minute");
        assertFalse(LutrisLibrary.fresh(-1, -1, 1_000, 62_000));
    }
}
