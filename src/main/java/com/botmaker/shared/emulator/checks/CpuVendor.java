package com.botmaker.shared.emulator.checks;

/** Who made the host's processor: it decides which ARM translator runs games best. */
public enum CpuVendor {
    AMD("amd", "AMD"),
    INTEL("intel", "Intel"),
    UNKNOWN("unknown", "unknown");

    private final String id;
    private final String displayName;

    CpuVendor(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static CpuVendor fromId(String id) {
        for (CpuVendor vendor : values()) {
            if (vendor != UNKNOWN && vendor.id.equals(id)) return vendor;
        }
        return UNKNOWN;
    }

    /** The vendor {@code /proc/cpuinfo}'s {@code vendor_id} line names; {@link #UNKNOWN} when there is none. */
    public static CpuVendor of(String cpuinfo) {
        if (cpuinfo == null) return UNKNOWN;
        for (String line : cpuinfo.split("\\R")) {
            if (!line.startsWith("vendor_id")) continue;
            if (line.contains("AuthenticAMD")) return AMD;
            if (line.contains("GenuineIntel")) return INTEL;
            return UNKNOWN;
        }
        return UNKNOWN;
    }
}
