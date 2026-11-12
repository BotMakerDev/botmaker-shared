package com.botmaker.shared.emulator.config;

/**
 * One thing a configuration says an instance should have (spec {@code 2026-11-12-emulators-window-design.md} § Fields).
 * Each product says which it can apply; one it cannot is shown as set by hand on that emulator, never as a change.
 */
public enum ConfigField {
    SCREEN("screen", "Screen", Section.SETTINGS, Kind.SCREEN, true, false),
    CPU_CORES("cpu_cores", "CPU cores", Section.SETTINGS, Kind.COUNT, true, false),
    MEMORY("memory", "Memory", Section.SETTINGS, Kind.MEGABYTES, true, false),
    FPS("fps", "FPS", Section.SETTINGS, Kind.COUNT, true, false),
    ROOT("root", "Root", Section.SETTINGS, Kind.TOGGLE, true, false),
    /** The product's own setting; the existing checks' Fix writes it with the instance stopped. */
    ADB("adb", "ADB", Section.SETTINGS, Kind.TOGGLE, true, false),
    LOCALE("locale", "Locale", Section.LOCALE, Kind.TEXT, false, false),
    TIME_ZONE("time_zone", "Time zone", Section.LOCALE, Kind.TEXT, false, false),
    DO_NOT_DISTURB("do_not_disturb", "Do Not Disturb in runs", Section.QUIET, Kind.TOGGLE, false, false),
    POPUPS_OFF("popups_off", "Popups and Gemini off", Section.QUIET, Kind.TOGGLE, false, false),
    PLAY_AUTO_UPDATE_OFF("play_auto_update_off", "Play auto-update off", Section.QUIET, Kind.TOGGLE, false, false),
    /** The emulator program's own updater: one per product, so it has no meaning on an instance. */
    PRODUCT_AUTO_UPDATE_OFF("product_auto_update_off", "Its auto-update off", Section.PRODUCT, Kind.TOGGLE, false, true),
    /** A field this build does not know, e.g. from a newer BotMaker's file. */
    UNKNOWN("unknown", "Unknown", Section.SETTINGS, Kind.TEXT, false, false);

    /** Where a field is listed. */
    public enum Section {
        SETTINGS("settings", "Settings"),
        LOCALE("locale", "Locale"),
        QUIET("quiet", "Quiet runs"),
        PRODUCT("product", "The emulator program");

        private final String id;
        private final String displayName;

        Section(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** The shape of a field's value: which {@link ConfigValue} it takes. */
    public enum Kind {
        SCREEN, COUNT, MEGABYTES, TOGGLE, TEXT
    }

    private final String id;
    private final String displayName;
    private final Section section;
    private final Kind kind;
    private final boolean needsRestart;
    private final boolean emulatorOnly;

    ConfigField(String id, String displayName, Section section, Kind kind, boolean needsRestart, boolean emulatorOnly) {
        this.id = id;
        this.displayName = displayName;
        this.section = section;
        this.kind = kind;
        this.needsRestart = needsRestart;
        this.emulatorOnly = emulatorOnly;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Section section() {
        return section;
    }

    public Kind kind() {
        return kind;
    }

    /** Applied only with the instance stopped: Apply stops a running one, then starts it again. */
    public boolean needsRestart() {
        return needsRestart;
    }

    /** Set on an emulator's configuration only, never on an instance's. */
    public boolean emulatorOnly() {
        return emulatorOnly;
    }

    public static ConfigField fromId(String id) {
        for (ConfigField field : values()) {
            if (field.id.equals(id)) {
                return field;
            }
        }
        return UNKNOWN;
    }
}
