package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a start command is run: from its program's folder, through ShellExecute where CreateProcess can't. */
class EmulatorLauncherTest {

    @Test
    void windowsRefusingForElevationIsError740() {
        assertTrue(EmulatorLauncher.needsElevation(new IOException(
                "Cannot run program \"HD-Player.exe\": CreateProcess error=740, The requested operation requires elevation")));
        assertFalse(EmulatorLauncher.needsElevation(new IOException("CreateProcess error=2, file not found")));
        assertFalse(EmulatorLauncher.needsElevation(new IOException((String) null)));
    }

    @Test
    void aProgramRunsFromItsOwnFolderAndABareNameFromHere(@TempDir Path dir) throws IOException {
        Path program = Files.writeString(dir.resolve("ldconsole.exe"), "");

        assertEquals(dir.toFile(), EmulatorLauncher.folderOf(program.toString()));
        assertNull(EmulatorLauncher.folderOf("powershell.exe"));
        assertNull(EmulatorLauncher.folderOf(dir.resolve("gone").resolve("x.exe").toString()));
    }
}
