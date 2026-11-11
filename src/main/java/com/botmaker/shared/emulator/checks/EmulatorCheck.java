package com.botmaker.shared.emulator.checks;

/**
 * One thing BotMaker checks about an emulator before a bot drives it, in the order the checks run: an earlier
 * one failing leaves the later ones unanswerable ({@link CheckState#AFTER_ABOVE}).
 */
public enum EmulatorCheck {
    WAYDROID_INSTALLED("waydroid_installed", "Waydroid installed"),
    WAYDROID_RUNNING("waydroid_running", "Container and session running"),
    EMULATOR_INSTALLED("emulator_installed", "Emulator installed"),
    EMULATOR_VERSION("emulator_version", "Tested version"),
    HYPERVISOR("hypervisor", "Windows hypervisor"),
    INSTANCE_EXISTS("instance_exists", "Instance found"),
    PORT_FREE("port_free", "ADB port its own"),
    ADB_ENABLED("adb_enabled", "ADB turned on"),
    HOST_MEMORY("host_memory", "Memory for it"),
    ADB_BOOTED("adb_booted", "Android started, adb connected"),
    INTERNET("internet", "Internet inside Android"),
    GOOGLE_PLAY("google_play", "Google Play"),
    ARM_TRANSLATOR("arm_translator", "ARM translator"),
    RESOLUTION("resolution", "Screen size"),
    DO_NOT_DISTURB("do_not_disturb", "Do Not Disturb during runs"),
    AVD_PLAY_IMAGE("avd_play_image", "Google Play system image"),
    AVD_MEMORY("avd_memory", "Memory"),
    UNKNOWN("unknown", "Unknown check");

    private final String id;
    private final String displayName;

    EmulatorCheck(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The id the assistant names it by. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The check {@code id} names, else {@link #UNKNOWN}. */
    public static EmulatorCheck fromId(String id) {
        for (EmulatorCheck check : values()) {
            if (check != UNKNOWN && check.id.equals(id == null ? null : id.trim())) return check;
        }
        return UNKNOWN;
    }
}
