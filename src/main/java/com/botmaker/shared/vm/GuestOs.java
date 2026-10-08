package com.botmaker.shared.vm;

/**
 * The system a game VM runs. Windows plays 3D and DirectX games, one game at a time on its one desktop; Linux gives
 * each bot a display of its own, so several games and bots share the VM, and plays 2D games (neither hypervisor
 * gives a Linux guest Vulkan here, so Proton draws in software).
 */
public enum GuestOs {
    WINDOWS("windows", "Windows"),
    LINUX("linux", "Linux"),
    UNKNOWN("unknown", "Unknown");

    private final String id;
    private final String displayName;

    GuestOs(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The system {@code id} names; {@link #UNKNOWN} for anything else. */
    public static GuestOs fromId(String id) {
        for (GuestOs os : values()) {
            if (os.id.equals(id)) return os;
        }
        return UNKNOWN;
    }
}
