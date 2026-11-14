package com.botmaker.shared.emulator;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
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
     * The package prefixes of each product's own apps, with the product they tell, read off the instances on
     * 2026-11-12 ({@code pm list packages}, the resumed activity at boot): LDPlayer's launcher and store, BlueStacks'
     * launcher, game center and its {@code gg.now} ad and account services ({@code com.uncube.gamevantage} came as an
     * ad), MuMu's store and helpers, MEmu's launcher. Each ends in a dot: a prefix is a vendor's namespace, never the
     * start of a word.
     */
    private static final Map<String, PlatformId> OWNERS = Map.of(
            "com.ldmnq.", PlatformId.LDPLAYER, "com.android.ld.", PlatformId.LDPLAYER,
            "com.uncube.", PlatformId.BLUESTACKS, "com.bluestacks.", PlatformId.BLUESTACKS,
            "gg.now.", PlatformId.BLUESTACKS,
            "com.mumu.", PlatformId.MUMU, "com.netease.mumu.", PlatformId.MUMU, "com.nemu.", PlatformId.MUMU,
            "com.microvirt.", PlatformId.MEMU);

    /** The prefixes of {@link #OWNERS}: an app under one is its emulator's own. */
    static final Set<String> PREFIXES = OWNERS.keySet();

    /**
     * The product whose Android holds {@code packages}, by its own apps: empty when none of them shows, or when two
     * products' do (nothing is told then).
     */
    static Optional<PlatformId> productOf(Collection<String> packages) {
        Set<PlatformId> found = EnumSet.noneOf(PlatformId.class);
        for (String pkg : packages) {
            OWNERS.forEach((prefix, product) -> {
                if (pkg.startsWith(prefix)) found.add(product);
            });
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }

    /**
     * Why the device {@code instance}'s address answers is not {@code instance}, in the user's words, else empty: its
     * packages ({@code pm list packages}) are another product's. Two products may share an address (BlueStacks and
     * LDPlayer both take 127.0.0.1:5555): what was driven then was the other one (seen live 2026-11-14, an LDPlayer
     * apply signing MSI App Player in).
     */
    public static Optional<String> notThis(EmulatorInstance instance, Collection<String> packages) {
        PlatformId wanted = instance.platformId();
        if (!OWNERS.containsValue(wanted)) return Optional.empty();
        return productOf(packages).filter(found -> found != wanted).map(found -> instance.adb().label() + " answers as "
                + found.displayName() + ", not " + wanted.displayName() + ": another emulator holds the address "
                + instance.name() + " uses. Stop it, or give " + instance.name() + " another ADB port, then try again.");
    }

    /** Launchers named whole: MuMu's, and Android's own that some products keep; any other is found as the home app. */
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
