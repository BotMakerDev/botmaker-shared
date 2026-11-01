package com.botmaker.shared.emulator.checks;

/** How a check came out. */
public enum CheckState {
    OK("ok", "OK"),
    FAILED("failed", "Failed"),
    /** Not asked: a check above it failed, and this one cannot be answered until that is fixed. */
    AFTER_ABOVE("after_above", "After the one above"),
    /** Said for the user's information; nothing to fix. */
    INFO("info", "Information"),
    UNKNOWN("unknown", "Unknown");

    private final String id;
    private final String displayName;

    CheckState(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static CheckState fromId(String id) {
        for (CheckState state : values()) {
            if (state != UNKNOWN && state.id.equals(id)) return state;
        }
        return UNKNOWN;
    }
}
