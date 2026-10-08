package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Windows command a launch target becomes inside a game VM. */
class GuestLaunchTest {

    @Test
    void eachWindowsKindBecomesAStartCommand() {
        assertEquals(Optional.of("start \"\" \"steam://rungameid/570\""), GuestLaunch.command(LaunchSpec.parse("steam:570")));
        assertEquals(Optional.of("start \"\" \"com.epicgames.launcher://apps/Fortnite?action=launch&silent=true\""),
                GuestLaunch.command(LaunchSpec.parse("epic:Fortnite")));
        assertEquals(Optional.of("start \"\" \"C:\\Games\\My Game\\game.exe\""),
                GuestLaunch.command(LaunchSpec.parse("exe:C:\\Games\\My Game\\game.exe")));
        assertEquals(Optional.of("start \"\" notepad.exe C:\\notes.txt"),
                GuestLaunch.command(LaunchSpec.parse("cli:notepad.exe C:\\notes.txt")));
    }

    @Test
    void whatAWindowsGuestCantStartOrWouldBreakTheLineIsEmpty() {
        assertEquals(Optional.empty(), GuestLaunch.command(LaunchSpec.parse("heroic:abc")));
        assertEquals(Optional.empty(), GuestLaunch.command(LaunchSpec.parse("steam:570 & del x")));
        assertEquals(Optional.empty(), GuestLaunch.command(LaunchSpec.parse("epic:a\"b")));
        assertEquals(Optional.empty(), GuestLaunch.command(LaunchSpec.parse("exe:C:\\a\"b.exe")));
    }

    @Test
    void aLinuxGuestStartsSteamAndEpicThroughItsLaunchersAndAWindowsProgramInWine() {
        assertEquals(Optional.of("steam -applaunch 570"), GuestLaunch.linuxCommand(LaunchSpec.parse("steam:570")));
        assertEquals(Optional.of("legendary launch Fortnite"), GuestLaunch.linuxCommand(LaunchSpec.parse("epic:Fortnite")));
        assertEquals(Optional.of("wine '/home/botmaker/Games/It'\\''s/game.EXE'"),
                GuestLaunch.linuxCommand(LaunchSpec.parse("exe:/home/botmaker/Games/It's/game.EXE")));
        assertEquals(Optional.of("'/opt/game/run'"), GuestLaunch.linuxCommand(LaunchSpec.parse("exe:/opt/game/run")));
        assertEquals(Optional.of("xmessage -center hi"), GuestLaunch.linuxCommand(LaunchSpec.parse("cli:xmessage -center hi")));
        assertEquals(Optional.empty(), GuestLaunch.linuxCommand(LaunchSpec.parse("steam:570 & rm x")));
        assertEquals(Optional.empty(), GuestLaunch.linuxCommand(LaunchSpec.parse("heroic:abc")));
    }

    @Test
    void theScriptExpandsVariablesAsCmdDoesAndReadsUtf8() {
        assertEquals("@echo off\r\nchcp 65001 >nul\r\nstart \"\" \"%ProgramFiles%\\é.exe\"\r\n"
                        + "del \"C:\\BotMaker\\launch.pending\" & exit /b\r\n",
                new String(GuestLaunch.script("start \"\" \"%ProgramFiles%\\é.exe\""), StandardCharsets.UTF_8));
    }
}
