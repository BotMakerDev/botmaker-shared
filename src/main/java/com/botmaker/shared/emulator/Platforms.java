package com.botmaker.shared.emulator;

import java.util.ArrayList;
import java.util.List;

/**
 * The registry of known {@link EmulatorPlatform}s and the one place to enumerate instances across all of
 * them. BlueStacks + LDPlayer discover for real today; MEmu/MuMu/Gameloop are scaffolded (discover nothing
 * yet). Add a product by adding it to {@link #ALL}.
 */
public final class Platforms {

    private Platforms() {}

    /** All known platforms, real discovery first. */
    public static final List<EmulatorPlatform> ALL = List.of(
            new BlueStacksPlatform(),
            new LdPlayerPlatform(),
            new MemuPlatform(),
            new MuMuPlatform(),
            new GameloopPlatform());

    /** Every discovered instance across every platform. Never throws; empty if nothing is installed. */
    public static List<EmulatorInstance> discoverAll() {
        List<EmulatorInstance> all = new ArrayList<>();
        for (EmulatorPlatform platform : ALL) {
            try {
                all.addAll(platform.discover());
            } catch (Exception ignored) {
                // a misbehaving platform never sinks the whole scan
            }
        }
        return all;
    }
}
