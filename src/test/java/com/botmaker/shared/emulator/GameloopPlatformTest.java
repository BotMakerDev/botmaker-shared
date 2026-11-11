package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Gameloop platform. Gameloop has no per-instance ADB-port config to parse (its engine
 * serves the primary instance on the fixed loopback port 5555), so the testable surface is the fixed
 * single-instance descriptor; live detection (registry / default engine path) is environment-dependent and
 * exercised only indirectly.
 */
class GameloopPlatformTest {

    @Test
    void singleInstanceUsesFixedLoopbackPort() {
        List<EmulatorInstance> instances = GameloopPlatform.singleInstance();
        assertEquals(1, instances.size());
        EmulatorInstance inst = instances.get(0);
        assertEquals(PlatformId.GAMELOOP, inst.platformId());
        assertEquals("Gameloop", inst.name());
        assertEquals("127.0.0.1:5555", inst.endpoint());
    }

    @Test
    void identityMatchesRegisteredPlatform() {
        GameloopPlatform platform = new GameloopPlatform();
        assertEquals(PlatformId.GAMELOOP, platform.id());
        assertEquals("Gameloop", platform.displayName());
        // discover() is best-effort and never throws, regardless of whether Gameloop is installed here.
        assertFalse(platform.discover() == null);
    }

    /** GameLoop 7's layout (2026-11-10): its launcher in Application, the Android image in Image\<version>. */
    @Test
    void gameLoop7StartsByItsLauncherOnceItsImageIsDownloaded(@TempDir Path install) throws IOException {
        Path launcher = file(install.resolve("Application").resolve("GameLoopLauncher.exe"));
        file(install.resolve("Application").resolve("7.0.167.0").resolve("GameLoop.exe"));

        assertTrue(GameloopPlatform.isInstall(install));
        assertFalse(GameloopPlatform.isInstall(install.resolve("Application")));
        assertNull(GameloopPlatform.engine(List.of(install)), "no Android image yet: launcher only");

        file(install.resolve("Image").resolve("5.10.7000.123").resolve("system.vhd"));
        assertEquals(new GameloopPlatform.Engine(launcher, List.of("GameLoopEmulator.exe"),
                        List.of("GameLoopEmulator.exe", "GameLoop.exe")),
                GameloopPlatform.engine(List.of(install)));
    }

    @Test
    void theOlderLayoutStartsItsEngineInUi(@TempDir Path install) throws IOException {
        Path engine = file(install.resolve("ui").resolve("AndroidEmulatorEn.exe"));

        assertTrue(GameloopPlatform.isInstall(install));
        assertEquals(engine, GameloopPlatform.engine(List.of(install)).launch());
    }

    private static Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "");
    }
}
