package com.botmaker.shared.launch;

/** Whether a launch target runs where it was launched, as far as that place can tell. */
public enum RunState {
    RUNNING("running", "running"),
    STOPPED("stopped", "not running"),
    /** It can't be told: a command line, a game whose launcher has no record of it, a place not answering. */
    UNKNOWN("unknown", "unknown");

    private final String id;
    private final String displayName;

    RunState(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The state {@code id} names; {@link #UNKNOWN} for anything else. */
    public static RunState fromId(String id) {
        for (RunState s : values()) {
            if (s.id.equals(id)) return s;
        }
        return UNKNOWN;
    }
}
