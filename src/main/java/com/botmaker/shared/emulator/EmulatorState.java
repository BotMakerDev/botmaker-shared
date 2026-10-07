package com.botmaker.shared.emulator;

/**
 * Whether an instance is up, as its own product says: {@code ldconsole list2}, {@code memuc listvms},
 * {@code MuMuManager info}, the BlueStacks or GameLoop engine process. {@link #UNKNOWN} when the product has no
 * such answer (Waydroid, a phone) or did not give one this time; the ADB port is then the only witness, which
 * is what {@link EmulatorLiveness} falls back to.
 *
 * <p>It exists because the port is not a witness on Windows. BlueStacks, LDPlayer's first instance and GameLoop
 * all ask for {@code 127.0.0.1:5555}, so whichever of them is running made every one of them look running.
 */
public enum EmulatorState {
    /** The product says Android is up in this instance. */
    RUNNING("running", "running"),
    /**
     * The instance's process is up, and Android may or may not be: LDPlayer and MuMu while they boot, and every
     * product that can only say its process runs (MEmu's {@code running} flag, a BlueStacks or GameLoop engine).
     */
    STARTING("starting", "starting…"),
    /** The product says this instance is not running. */
    STOPPED("stopped", "stopped"),
    /** The product did not say. */
    UNKNOWN("unknown", "unknown");

    private final String id;
    private final String displayName;

    EmulatorState(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** A stable lowercase key. */
    public String id() {
        return id;
    }

    /** What a picker row says. */
    public String displayName() {
        return displayName;
    }

    /** The state for a stored {@link #id()}; {@link #UNKNOWN} for null or anything unrecognised. */
    public static EmulatorState fromId(String id) {
        for (EmulatorState state : values()) {
            if (state.id.equals(id)) return state;
        }
        return UNKNOWN;
    }
}
