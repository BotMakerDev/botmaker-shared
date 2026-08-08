package com.botmaker.shared.emulator;

import java.time.Duration;

/**
 * Which emulator product an instance belongs to. This replaces the free-form {@code String platformId} that
 * used to be stamped onto every {@link EmulatorInstance}: the set of products is closed and known here, so a
 * typo can't invent one and a consumer can {@code switch} over it exhaustively.
 *
 * <p>Each constant carries both its stable wire {@link #id()} (the key that was previously the raw string —
 * unchanged, so any stored value still resolves) and its {@link #displayName()} for UI and logs. The display
 * name lives here rather than in each consumer, so the pickers can't disagree about what to call a product.
 *
 * <p>Not a Jackson type — shared deliberately has no Jackson dependency. Anything persisting a platform
 * should write {@link #id()} and read it back through {@link #fromId}, which is total: an unrecognised or
 * missing id yields {@link #UNKNOWN} rather than throwing, so an older config naming a product this build
 * doesn't have still loads.
 */
public enum PlatformId {

    BLUESTACKS("bluestacks", "BlueStacks"),
    LDPLAYER("ldplayer", "LDPlayer"),
    MEMU("memu", "MEmu"),
    MUMU("mumu", "MuMu Player"),
    GAMELOOP("gameloop", "Gameloop"),

    /**
     * Waydroid — the odd one out. It is a Linux-native Android container rather than a Windows emulator
     * product, so it has no registry key, no install directory to scan and no per-instance config: there is
     * one container per machine. It is here anyway because from a bot's point of view it is exactly the same
     * thing — an Android surface reachable over ADB — and treating it as a separate concept would fork every
     * picker and every launch path for no gain.
     */
    WAYDROID("waydroid", "Waydroid"),

    /**
     * A physical phone or tablet — the second odd one out, and for the same reason {@link #WAYDROID} is one.
     * It has no install directory, no per-instance config and nothing to launch; it is discovered from the
     * host's adb server or from an address the user stated ({@link DevicePlatform}). It belongs in this enum
     * because a bot cannot tell the difference: it is an Android surface reachable over ADB, and the entire
     * stack above {@link AdbDevice} — capture source, launch target, pilot route, capture target — already
     * works on exactly that.
     */
    PHYSICAL("device", "Android device"),

    /** A product this build doesn't know — e.g. an id from a newer or hand-edited config. */
    UNKNOWN("unknown", "Emulator");

    private final String id;
    private final String displayName;

    PlatformId(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The stable product key, e.g. {@code "bluestacks"}. Safe to persist. */
    public String id() {
        return id;
    }

    /** The product's human-readable name for UI and logs, e.g. {@code "BlueStacks"}. */
    public String displayName() {
        return displayName;
    }

    /**
     * How long this product may take to go from "start dispatched" to "ready to be driven" — the ceiling any
     * caller polling {@link EmulatorReadiness#awaitReady} should use.
     *
     * <p>It lives on the product because it <em>is</em> a property of the product, and because the number had
     * already been guessed three times in two modules: the launcher allowed 120 s, Studio's picker 90 s with a
     * 240 s Waydroid special case. The launcher held the shortest one, which is why an emulator app never
     * started — Waydroid was still booting when it gave up. Waydroid is not a process start but a container
     * start, a session start and a full Android boot behind a compositor, so it routinely takes minutes where
     * a console-tool product takes seconds; one shared ceiling would either cut it off or leave a dead
     * LDPlayer spinning for four minutes.
     */
    public Duration bootTimeout() {
        return this == WAYDROID ? Duration.ofSeconds(240) : Duration.ofSeconds(90);
    }

    /** The platform for a stored {@link #id()}; {@link #UNKNOWN} for null or anything unrecognised. */
    public static PlatformId fromId(String id) {
        if (id == null) return UNKNOWN;
        for (PlatformId p : values()) {
            if (p.id.equalsIgnoreCase(id)) return p;
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return id;
    }
}
