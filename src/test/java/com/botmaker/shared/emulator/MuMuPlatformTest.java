package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the MuMu Player 12 instance parser — no MuMu install required. The ADB port is derived from
 * the folder index: {@code 16384 + 32*index}.
 */
class MuMuPlatformTest {

    @Test
    void derivesPortFromIndexAndReadsPlayerName() {
        Optional<EmulatorInstance> zero =
                MuMuPlatform.parseInstance("MuMuPlayer-12.0-0", "{\"playerName\":\"Main\"}");
        assertTrue(zero.isPresent());
        assertEquals("mumu", zero.get().platformId());
        assertEquals("Main", zero.get().name());
        assertEquals("127.0.0.1", zero.get().host());
        assertEquals(16384, zero.get().adbPort());

        Optional<EmulatorInstance> one =
                MuMuPlatform.parseInstance("MuMuPlayer-12.0-1", "{\"playerName\":\"Alt\"}");
        assertTrue(one.isPresent());
        assertEquals("Alt", one.get().name());
        assertEquals(16416, one.get().adbPort());
    }

    @Test
    void supportsGlobalBuildFolderName() {
        Optional<EmulatorInstance> inst =
                MuMuPlatform.parseInstance("MuMuPlayerGlobal-12.0-2", "{\"playerName\":\"Global\"}");
        assertTrue(inst.isPresent());
        assertEquals("Global", inst.get().name());
        assertEquals(16448, inst.get().adbPort());
    }

    @Test
    void fallsBackToMuMuIndexWhenNoPlayerName() {
        Optional<EmulatorInstance> inst = MuMuPlatform.parseInstance("MuMuPlayer-12.0-3", "{}");
        assertTrue(inst.isPresent());
        assertEquals("MuMu-3", inst.get().name());
        assertEquals(16480, inst.get().adbPort());

        Optional<EmulatorInstance> noConfig = MuMuPlatform.parseInstance("MuMuPlayer-12.0-0", "");
        assertTrue(noConfig.isPresent());
        assertEquals("MuMu-0", noConfig.get().name());
    }

    @Test
    void rejectsNonInstanceFolderNames() {
        assertFalse(MuMuPlatform.parseInstance("MuMuPlayer-12.0", "{}").isPresent());
        assertFalse(MuMuPlatform.parseInstance("nemu", "{}").isPresent());
        assertFalse(MuMuPlatform.parseInstance("vms", "{}").isPresent());
    }

    @Test
    void attachesMuMuManagerControlCommands() {
        EmulatorInstance base = MuMuPlatform.parseInstance("MuMuPlayer-12.0-1", "{}").orElseThrow();
        Path console = Path.of("C:\\MuMu\\shell\\MuMuManager.exe");
        EmulatorInstance withCmd = MuMuPlatform.withLaunch(base, 1, console);
        assertEquals(List.of(console.toString(), "control", "-v", "1", "launch"), withCmd.launchCommand());
        assertEquals(List.of(console.toString(), "control", "-v", "1", "shutdown"), withCmd.stopCommand());
    }
}
