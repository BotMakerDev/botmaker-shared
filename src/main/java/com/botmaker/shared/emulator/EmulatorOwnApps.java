package com.botmaker.shared.emulator;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The apps an emulator puts over a game by itself: its launcher, its store, its game center and its ads. A run brings
 * the game back in front of one (the user's call, 2026-11-01, phase 4 of the Windows emulators plan); any other app
 * in front — Google's sign-in, Play, a browser the game opened — is the game's own business and is left alone.
 */
public final class EmulatorOwnApps {

    private EmulatorOwnApps() {}

    /**
     * The package prefixes of each product's own apps, read off the instances on 2026-11-12 ({@code pm list packages},
     * the resumed activity at boot): LDPlayer's launcher and store, BlueStacks' launcher, game center and its
     * {@code gg.now} ad and account services ({@code com.uncube.gamevantage} came as an ad), MuMu's store and helpers,
     * MEmu's launcher. Each ends in a dot: a prefix is a vendor's namespace, never the start of a word.
     */
    static final List<String> PREFIXES = List.of(
            "com.ldmnq.", "com.android.ld.",
            "com.uncube.", "com.bluestacks.", "gg.now.",
            "com.mumu.", "com.netease.mumu.", "com.nemu.",
            "com.microvirt.");

    /** Launchers named whole: MuMu's, and Android's own that some products keep. GameLoop's is found as the home app. */
    static final Set<String> LAUNCHERS = Set.of("app.lawnchair", "com.android.launcher", "com.android.launcher3");

    /**
     * Whether {@code front} is an app of the emulator's own rather than the game's: Android's home app
     * ({@code home}), one of {@link #LAUNCHERS}, or one in {@link #PREFIXES}.
     */
    public static boolean covers(String front, Optional<String> home) {
        if (front == null || front.isEmpty()) return false;
        if (home.filter(front::equals).isPresent() || LAUNCHERS.contains(front)) return true;
        return PREFIXES.stream().anyMatch(front::startsWith);
    }
}
