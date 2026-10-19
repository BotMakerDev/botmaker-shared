package com.botmaker.shared.launch;

import com.botmaker.shared.platform.Os;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Steam's shortcut ids, Bottles, the Start menu and launcher links (2026-10-19). */
class MoreLaunchersTest {

    @Test
    void aShortcutsGameIdCarriesItsAppIdAboveTheFlag() {
        String gameId = SteamIds.shortcutGameId(0xC0A81234);
        assertTrue(SteamIds.isShortcut(gameId));
        assertEquals(Long.toString(0xC0A81234L), SteamIds.appId(gameId), "the reaper's AppId is unsigned");
        assertEquals(Long.toUnsignedString((0xC0A81234L << 32) | 0x02000000L), gameId);
        assertFalse(SteamIds.isShortcut("570"));
        assertEquals("570", SteamIds.appId("570"));
        assertTrue(SteamIds.legacyShortcutAppId("\"/usr/bin/game\"", "Game") < 0, "its top bit is set");
    }

    @Test
    void aSteamShortcutStartsByItsLinkAndRunsUnderItsAppId() {
        String gameId = SteamIds.shortcutGameId(42);
        assertEquals(List.of("steam", "steam://rungameid/" + gameId), LaunchCommands.steam(gameId).get(0));
        assertEquals(List.of("steam", "-applaunch", "570"), LaunchCommands.steam("570").get(0));
        assertEquals("AppId=42", new LaunchSpec(LaunchKind.STEAM, gameId).runningToken());
    }

    @Test
    void aBottlesTargetSplitsAtItsFirstSlashAndRunsAsOurChild() {
        LaunchSpec spec = LaunchSpec.parse("bottles:Gaming/Battle.net / EU");
        assertEquals("Gaming", spec.bottle());
        assertEquals("Battle.net / EU", spec.bottleProgram());
        assertNull(LaunchSpec.parse("bottles:Gaming").bottle(), "no program");
        assertNull(LaunchSpec.parse("exe:/a/b").bottle());
        assertEquals(List.of(List.of("bottles-cli", "run", "-b", "Gaming", "-p", "Battle.net / EU"),
                        List.of("flatpak", "run", "--command=bottles-cli", "com.usebottles.bottles", "run", "-b",
                                "Gaming", "-p", "Battle.net / EU")),
                LaunchCommands.childLadder(spec));
        assertFalse(LaunchKind.BOTTLES.routesThroughDaemon(), "an open Bottles needs no closing");
        assertNull(spec.runningToken(), "looked up in its bottle instead");
    }

    @Test
    void aBottleYmlListsItsProgramsByName() {
        List<BottlesLibrary.Program> programs = BottlesLibrary.parse("folder", """
                Arch: win64
                External_Programs:
                  1f2e3d:
                    arguments: ''
                    executable: Battle.net Launcher.exe
                    id: 1f2e3d
                    name: 'Battle.net'
                    path: /home/u/.local/share/bottles/bottles/Gaming/drive_c/Battle.net Launcher.exe
                  4a5b6c:
                    executable: game.exe
                    name: "Ark's Game"
                Name: Gaming
                Runner: soda-7.0-9
                """);
        assertEquals(List.of(new BottlesLibrary.Program("Gaming", "Ark's Game", "game.exe"),
                new BottlesLibrary.Program("Gaming", "Battle.net", "Battle.net Launcher.exe")), programs);
        assertEquals("bottles:Gaming/Battle.net", programs.get(1).spec().spec());
        assertEquals(List.of(), BottlesLibrary.parse("Empty", "External_Programs: {}\nName: Empty\n"));
    }

    @Test
    void theStartMenuIsReadAsAppsAndItsLauncherLinksAsTheirGames() {
        List<DesktopEntries.Entry> entries = WindowsApps.parse("""
                [{"Name":"Notepad","AppID":"{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\\\notepad.exe"},
                 {"Name":"Forza Horizon 5","AppID":"Microsoft.624F8B84B80_8wekyb3d8bbwe!ForzaHorizon5"},
                 {"Name":"Dota 2","AppID":"steam://rungameid/570"},
                 {"Name":"Overwatch","AppID":"battlenet://Pro"},
                 {"Name":"Anno 1800","AppID":"uplay://launch/4553/0"},
                 {"Name":"Fortnite","AppID":"com.epicgames.launcher://apps/Fortnite?action=launch"},
                 {"Name":"My Game","AppID":"D:\\\\Games\\\\My Game\\\\game.exe"}]
                """, variable -> variable.equals("SystemRoot\\System32") ? "C:\\Windows\\System32" : null);
        Map<String, DesktopEntries.Entry> byName = new java.util.HashMap<>();
        entries.forEach(e -> byName.put(e.name(), e));

        assertEquals("C:\\Windows\\System32\\notepad.exe", byName.get("Notepad").icon());
        assertEquals(List.of("notepad.exe"), DesktopEntries.tokensOf(byName.get("Notepad")));
        assertEquals(List.of("game.exe"), DesktopEntries.tokensOf(byName.get("My Game")), "a path with spaces");
        assertEquals("D:\\Games\\My Game\\game.exe", byName.get("My Game").icon());
        assertEquals(List.of(), DesktopEntries.tokensOf(byName.get("Forza Horizon 5")), "a Store id is no program");
        assertEquals("desktop:Microsoft.624F8B84B80_8wekyb3d8bbwe!ForzaHorizon5",
                byName.get("Forza Horizon 5").spec().spec());

        assertEquals(Optional.of(LaunchSpec.parse("steam:570")), byName.get("Dota 2").shortcutFor());
        assertEquals(Optional.of(LaunchSpec.parse("url:battlenet://Pro")), byName.get("Overwatch").shortcutFor());
        assertEquals(Optional.of(LaunchSpec.ubisoft("4553")), byName.get("Anno 1800").shortcutFor());
        assertEquals(Optional.of(LaunchSpec.parse("epic:Fortnite")), byName.get("Fortnite").shortcutFor());

        assertEquals(List.of(), WindowsApps.parse("not json", v -> null));
        assertEquals(1, WindowsApps.parse("{\"Name\":\"One\",\"AppID\":\"One.App!App\"}", v -> null).size(),
                "PowerShell writes a lone app as an object");
    }

    @Test
    void anAppRunsOnTheSystemWhoseMenuItsIdComesFrom() {
        assertTrue(LaunchSpec.parse("desktop:Microsoft.X_8wekyb3d8bbwe!App").runsOn(Os.WINDOWS));
        assertFalse(LaunchSpec.parse("desktop:Microsoft.X_8wekyb3d8bbwe!App").runsOn(Os.LINUX));
        assertTrue(LaunchSpec.parse("desktop:D:\\Games\\game.exe").runsOn(Os.WINDOWS));
        assertFalse(LaunchSpec.parse("desktop:org.kde.kpat").runsOn(Os.WINDOWS), "a menu id starts nothing there");
        assertTrue(LaunchSpec.parse("desktop:org.kde.kpat").runsOn(Os.LINUX));
        assertTrue(LaunchSpec.parse("desktop:/Applications/Chess.app").runsOn(Os.MAC));
    }

    @Test
    void anEpicShortcutsEncodedAppIsItsAppName() {
        assertEquals(Optional.of(LaunchSpec.parse("epic:Fortnite")), DesktopEntries.shortcutIn(
                "com.epicgames.launcher://apps/fn%3A4fe75bbc5a674f4f9b356b5c90567da5%3AFortnite?action=launch"));
    }

    @Test
    void aStartMenuAppStartsThroughTheAppsFolder() {
        assertEquals(List.of(List.of("explorer.exe", "shell:AppsFolder\\Microsoft.X_8wekyb3d8bbwe!App")),
                LaunchCommands.desktop("Microsoft.X_8wekyb3d8bbwe!App", Os.WINDOWS));
    }

    @Test
    void aLinkHasNoChildCommandAndNoProcessOfItsOwn() {
        LaunchSpec link = LaunchSpec.parse("url:uplay://launch/4553/0");
        assertEquals(LaunchKind.URL, link.kind());
        assertEquals("uplay://launch/4553/0", link.token());
        assertEquals(List.of(), LaunchCommands.childLadder(link));
        assertNull(link.runningToken());
    }
}
