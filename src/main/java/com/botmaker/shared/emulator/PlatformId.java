package com.botmaker.shared.emulator;

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
