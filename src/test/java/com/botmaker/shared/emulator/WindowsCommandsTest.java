package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The PowerShell lines that start and stop BlueStacks, read back from their encoded form. */
class WindowsCommandsTest {

    /** The script a {@code powershell.exe -EncodedCommand} line runs. */
    private static String script(List<String> command) {
        assertEquals("powershell.exe", command.getFirst());
        assertEquals("-EncodedCommand", command.get(command.size() - 2));
        return WindowsCommands.decoded(command.getLast());
    }

    @Test
    void aShellExecuteRunsTheProgramFromItsFolderWithItsArguments() {
        String script = script(WindowsCommands.shellExecute(List.of(
                "C:\\Program Files\\BlueStacks_msi5\\HD-Player.exe", "--instance", "my vm")));

        assertEquals("Start-Process -FilePath 'C:\\Program Files\\BlueStacks_msi5\\HD-Player.exe'"
                + " -WorkingDirectory 'C:\\Program Files\\BlueStacks_msi5'"
                + " -ArgumentList '--instance','\"my vm\"'", script);
    }

    @Test
    void aBlueStacksStopEndsThePlayerOfItsTokenOrItsWindowAndNeverAsksForRights() {
        String script = script(WindowsCommands.stopBlueStacks(
                Path.of("C:\\Program Files\\BlueStacks_nxt\\HD-Player.exe"), "Pie64_1", "Bob's Pie"));

        assertTrue(script.contains("$_.CommandLine -match '--instance\\s+\"?Pie64_1\"?(\\s|$)'"), script);
        // Another edition's player of the same token is left alone, when its path can be read.
        assertTrue(script.contains("$_.ExecutablePath -eq 'C:\\Program Files\\BlueStacks_nxt\\HD-Player.exe'"), script);
        assertTrue(script.contains(".MainWindowTitle -eq 'Bob''s Pie'"), script);
        assertTrue(script.contains("Stop-Process -Id $q.ProcessId -Force"));
        // Asked to close, BlueStacks asks "Do you really want to close?" and waits.
        assertFalse(script.contains("CloseMainWindow"), script);
        assertFalse(script.contains("RunAs"), script);
    }

    @Test
    void aTokenIsMatchedLiterally() {
        assertTrue(WindowsCommands.stopBlueStacksScript(Path.of("C:\\B\\HD-Player.exe"), "Nougat.64", "x")
                .contains("Nougat\\.64"));
    }

    @Test
    void aQuoteInAPathIsDoubled() {
        assertEquals("'C:\\Bob''s\\x.exe'", WindowsCommands.quoted("C:\\Bob's\\x.exe"));
    }
}
