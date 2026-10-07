package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LutrisLibraryTest {

    @Test
    void theJsonListIsReadPastLutrisOwnLogLines() {
        Map<String, LutrisLibrary.Game> games = LutrisLibrary.parse("""
                2026-10-07 11:00:00,000: Startup info
                [
                  {"id": 3, "slug": "epic-games-store", "name": "Epic Games Store", "runner": "wine",
                   "coverPath": "/nowhere/epic-games-store.jpg"},
                  {"id": 1, "slug": "osu", "name": "osu!", "coverPath": null}
                ]
                """);
        assertEquals(List.of("3", "1"), List.copyOf(games.keySet()), "Lutris's own order");
        assertEquals("Epic Games Store", games.get("3").name());
        assertNull(games.get("3").cover(), "a cover file that doesn't exist is no cover");
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
}
