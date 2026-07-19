package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the LDPlayer {@code leidian<index>.config} parser — no LDPlayer install required.
 * The ADB port is derived from the file index: {@code 5555 + 2*index}. The player name is read with a regex
 * that matches both the flat {@code "statusSettings.playerName"} key and the nested {@code "playerName"} form.
 */
class LdPlayerPlatformTest {

    @Test
    void derivesPortFromIndexAndReadsPlayerName() {
        Optional<EmulatorInstance> zero =
                LdPlayerPlatform.parseInstance("leidian0.config", "{\"statusSettings.playerName\":\"LDPlayer\"}");
        assertTrue(zero.isPresent());
        assertEquals("ldplayer", zero.get().platformId());
        assertEquals("LDPlayer", zero.get().name());
        assertEquals(5555, zero.get().adbPort());

        Optional<EmulatorInstance> two =
                LdPlayerPlatform.parseInstance("leidian2.config", "{\"statusSettings.playerName\":\"Alt\"}");
        assertTrue(two.isPresent());
        assertEquals("Alt", two.get().name());
        assertEquals(5559, two.get().adbPort());
    }

    @Test
    void readsNestedPlayerNameForm() {
        Optional<EmulatorInstance> inst = LdPlayerPlatform.parseInstance(
                "leidian3.config", "{\"statusSettings\":{\"playerName\":\"Nested\"}}");
        assertTrue(inst.isPresent());
        assertEquals("Nested", inst.get().name());
        assertEquals(5561, inst.get().adbPort());
    }

    @Test
    void fallsBackToLeidianNameWhenJsonHasNoPlayerName() {
        Optional<EmulatorInstance> inst = LdPlayerPlatform.parseInstance("leidian1.config", "{}");
        assertTrue(inst.isPresent());
        assertEquals("leidian1", inst.get().name());
        assertEquals(5557, inst.get().adbPort());
    }

    @Test
    void rejectsNonInstanceFileNames() {
        assertFalse(LdPlayerPlatform.parseInstance("leidian.config", "{}").isPresent());
        assertFalse(LdPlayerPlatform.parseInstance("vmconfig.json", "{}").isPresent());
    }

    @Test
    void survivesMalformedJson() {
        Optional<EmulatorInstance> inst = LdPlayerPlatform.parseInstance("leidian0.config", "not json {");
        assertTrue(inst.isPresent());
        assertEquals("leidian0", inst.get().name());
        assertEquals(5555, inst.get().adbPort());
    }

    @Test
    void attachesLdconsoleLaunchAndQuitCommands() {
        EmulatorInstance base = LdPlayerPlatform.parseInstance("leidian2.config", "{}").orElseThrow();
        EmulatorInstance withCmd = LdPlayerPlatform.withLaunch(base, 2, Path.of("C:\\LDPlayer\\ldconsole.exe"));
        assertTrue(withCmd.canLaunch());
        assertTrue(withCmd.canStop());
        assertEquals(List.of("C:\\LDPlayer\\ldconsole.exe", "launch", "--index", "2"), withCmd.launchCommand());
        assertEquals(List.of("C:\\LDPlayer\\ldconsole.exe", "quit", "--index", "2"), withCmd.stopCommand());
    }

    @Test
    void withLaunchNoConsoleLeavesInstanceUnchanged() {
        EmulatorInstance base = LdPlayerPlatform.parseInstance("leidian0.config", "{}").orElseThrow();
        assertFalse(LdPlayerPlatform.withLaunch(base, 0, null).canLaunch());
    }
}
