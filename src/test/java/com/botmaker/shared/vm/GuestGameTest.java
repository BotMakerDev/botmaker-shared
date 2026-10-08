package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.RunState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding and stopping a launch target's processes in a Windows guest. */
class GuestGameTest {

    @Test
    void aPathIsAProgramWithAName() {
        assertEquals(Optional.of("game.exe"), GuestGame.programName(LaunchSpec.parse("exe:C:\\Games\\My Game\\game.exe")));
        assertEquals(Optional.of("notepad.exe"), GuestGame.programName(LaunchSpec.parse("exe:%windir%\\notepad.exe")));
        assertEquals(Optional.of("notepad.exe"), GuestGame.programName(LaunchSpec.parse("exe:notepad")));
        assertEquals(Optional.empty(), GuestGame.programName(LaunchSpec.parse("exe:C:\\Users\\Public\\Desktop\\Game.lnk")));
        assertEquals(Optional.empty(), GuestGame.programName(LaunchSpec.parse("exe:C:\\Games\\")));
    }

    @Test
    void aPathIsItsFolderUnlessThatFolderHoldsMoreThanTheGame() {
        String exe = GuestGame.script(LaunchSpec.parse("exe:C:\\Games\\It's\\game.exe"), false).orElseThrow();
        assertTrue(exe.contains("ExpandEnvironmentVariables('C:\\Games\\It''s\\game.exe')"), exe);
        assertTrue(exe.contains("Split('\\').Count -gt 2"), exe);
        assertTrue(exe.contains("StartsWith($env:windir + '\\'"), exe);
        assertTrue(exe.contains("else { $name = 'game.exe' }"), exe);
    }

    @Test
    void eachKindFindsTheGameWhereItsLauncherRecordsIt() {
        String steam = GuestGame.script(LaunchSpec.parse("steam:570"), false).orElseThrow();
        assertTrue(steam.contains("'C:\\Program Files (x86)\\Steam'"), steam);
        assertTrue(steam.contains("appmanifest_570.acf"), steam);
        String epic = GuestGame.script(LaunchSpec.parse("epic:Fortnite"), false).orElseThrow();
        assertTrue(epic.contains("$m.AppName -eq 'Fortnite'"), epic);
        assertTrue(epic.contains(GameCopy.EPIC_MANIFESTS), epic);
        assertFalse(epic.contains("taskkill"), epic);
        String stop = GuestGame.script(LaunchSpec.parse("epic:Fortnite"), true).orElseThrow();
        assertTrue(stop.contains("taskkill.exe /F /T /PID"), stop);
        assertTrue(stop.contains("\"" + GuestGame.SURVIVED + "`t"), stop);
    }

    @Test
    void whatTheGuestCantTellApartHasNoScript() {
        assertEquals(Optional.empty(), GuestGame.script(LaunchSpec.parse("cli:notepad.exe C:\\notes.txt"), false));
        assertEquals(Optional.empty(), GuestGame.script(LaunchSpec.parse("steam:570 & del x"), false));
        assertEquals(Optional.empty(), GuestGame.script(LaunchSpec.parse("exe:C:\\Game.lnk"), false));
    }

    @Test
    void theOutputSaysRunningStoppedOrUnknown() {
        assertEquals(new GuestGame.Found(RunState.RUNNING, List.of("Notepad.exe (4242)", "game.exe (17)"), List.of()),
                GuestGame.parse("4242\tNotepad.exe\r\n17\tgame.exe\r\n"));
        assertEquals(new GuestGame.Found(RunState.STOPPED, List.of(), List.of()), GuestGame.parse(""));
        assertEquals(new GuestGame.Found(RunState.RUNNING, List.of("game.exe (17)"), List.of("game (17)")),
                GuestGame.parse("17\tgame.exe\r\nsurvived\t17\tgame\r\n"));
        assertEquals(GuestGame.Found.UNKNOWN, GuestGame.parse(GuestGame.NOT_INSTALLED + "\r\n"));
    }
}
