package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertEquals("gameloop", inst.platformId());
        assertEquals("Gameloop", inst.name());
        assertEquals("127.0.0.1", inst.host());
        assertEquals(5555, inst.adbPort());
        assertEquals("127.0.0.1:5555", inst.endpoint());
    }

    @Test
    void identityMatchesRegisteredPlatform() {
        GameloopPlatform platform = new GameloopPlatform();
        assertEquals("gameloop", platform.id());
        assertEquals("Gameloop", platform.displayName());
        // discover() is best-effort and never throws, regardless of whether Gameloop is installed here.
        assertFalse(platform.discover() == null);
    }
}
