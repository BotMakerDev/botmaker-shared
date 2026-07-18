package com.botmaker.shared.emulator;

import java.util.List;

/**
 * Scaffolds for the not-yet-implemented emulator products. Each one is a valid {@link EmulatorPlatform} that
 * discovers nothing yet, so it can be listed in {@link Platforms} today and filled in later by adding its
 * config parser (the transport underneath already works). Grouped in one file because they carry no logic.
 *
 * <p>TODO(bluestacks/ldplayer done): implement discovery for these:
 * <ul>
 *   <li><b>MEmu</b> — instances under {@code <install>\MemuHyperv VMs}; ADB port in each VM's {@code .memu}
 *       config (or {@code memuc listvms} enumerates name + adb port).</li>
 *   <li><b>MuMu Player</b> — {@code vms\*} config; MuMu 12 exposes per-instance ADB ports (16384-based).</li>
 *   <li><b>Gameloop</b> — Tencent AndroidEmulator; ADB port discoverable from its config/registry.</li>
 * </ul>
 */
final class ScaffoldPlatforms {
    private ScaffoldPlatforms() {}
}

/** MEmu — discovery not implemented yet (see {@link ScaffoldPlatforms}). */
final class MemuPlatform implements EmulatorPlatform {
    static final String PLATFORM_ID = "memu";

    @Override public String id() { return PLATFORM_ID; }
    @Override public String displayName() { return "MEmu"; }
    @Override public List<EmulatorInstance> discover() { return List.of(); }
}

/** MuMu Player — discovery not implemented yet (see {@link ScaffoldPlatforms}). */
final class MuMuPlatform implements EmulatorPlatform {
    static final String PLATFORM_ID = "mumu";

    @Override public String id() { return PLATFORM_ID; }
    @Override public String displayName() { return "MuMu Player"; }
    @Override public List<EmulatorInstance> discover() { return List.of(); }
}

/** Gameloop — discovery not implemented yet (see {@link ScaffoldPlatforms}). */
final class GameloopPlatform implements EmulatorPlatform {
    static final String PLATFORM_ID = "gameloop";

    @Override public String id() { return PLATFORM_ID; }
    @Override public String displayName() { return "Gameloop"; }
    @Override public List<EmulatorInstance> discover() { return List.of(); }
}
