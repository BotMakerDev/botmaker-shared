package com.botmaker.shared.emulator;

import java.util.List;

/**
 * Scaffolds for the not-yet-implemented emulator products. Each one is a valid {@link EmulatorPlatform} that
 * discovers nothing yet, so it can be listed in {@link Platforms} today and filled in later by adding its
 * config parser (the transport underneath already works).
 *
 * <p>BlueStacks, LDPlayer, MEmu and MuMu now discover for real (their own classes). Still scaffolded:
 * <ul>
 *   <li><b>Gameloop</b> — Tencent AndroidEmulator; ADB port discoverable from its config/registry.</li>
 * </ul>
 */
final class ScaffoldPlatforms {
    private ScaffoldPlatforms() {}
}

/** Gameloop — discovery not implemented yet (see {@link ScaffoldPlatforms}). */
final class GameloopPlatform implements EmulatorPlatform {
    static final String PLATFORM_ID = "gameloop";

    @Override public String id() { return PLATFORM_ID; }
    @Override public String displayName() { return "Gameloop"; }
    @Override public List<EmulatorInstance> discover() { return List.of(); }
}
