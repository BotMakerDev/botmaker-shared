package com.botmaker.shared.emulator.checks;

import java.util.Set;

/**
 * The layer that lets an x86 Android run a game's ARM code, declared by {@code ro.dalvik.vm.native.bridge}.
 * libhoudini is Intel's and runs some ARM games badly on AMD processors; libndk_translation is Google's and is
 * the usual answer there (2026-11-01: Sword x Staff hung after its first screen with libhoudini on a Ryzen).
 */
public enum Translator {
    LIBNDK("libndk", "libndk", "libndk_translation.so"),
    LIBHOUDINI("libhoudini", "libhoudini", "libhoudini.so"),
    NONE("none", "no ARM translator", ""),
    UNKNOWN("unknown", "an unknown ARM translator", null);

    /** The property a translator declares itself under. */
    public static final String BRIDGE_PROP = "ro.dalvik.vm.native.bridge";

    /** The Android versions BotMaker has a pinned build of each translator for. */
    private static final Set<String> PINNED_RELEASES = Set.of("11", "13");

    private final String id;
    private final String displayName;
    private final String library;

    Translator(String id, String displayName, String library) {
        this.id = id;
        this.displayName = displayName;
        this.library = library;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The library {@link #BRIDGE_PROP} names for it; empty for {@link #NONE}, null for {@link #UNKNOWN}. */
    public String library() {
        return library;
    }

    public static Translator fromId(String id) {
        for (Translator translator : values()) {
            if (translator != UNKNOWN && translator.id.equals(id)) return translator;
        }
        return UNKNOWN;
    }

    /** The translator {@link #BRIDGE_PROP}'s value names: blank or absent is {@link #NONE}. */
    public static Translator ofBridgeProp(String value) {
        if (value == null || value.isBlank() || value.trim().equals("0")) return NONE;
        for (Translator translator : new Translator[]{LIBNDK, LIBHOUDINI}) {
            if (value.trim().equals(translator.library)) return translator;
        }
        return UNKNOWN;
    }

    /** The one that runs ARM games best on {@code vendor}'s processors; {@link #UNKNOWN} when it is unknown. */
    public static Translator suitedTo(CpuVendor vendor) {
        return switch (vendor) {
            case AMD -> LIBNDK;
            case INTEL -> LIBHOUDINI;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /** Whether BotMaker has a pinned build for Android {@code release} ({@code ro.build.version.release}). */
    public static boolean pinned(String release) {
        return release != null && PINNED_RELEASES.contains(release.trim());
    }
}
