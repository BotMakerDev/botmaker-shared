package com.botmaker.shared.vm;

/**
 * The products that can run a game VM on this computer. Both serve the guest's screen over VNC on loopback,
 * so a bot drives either through {@link com.botmaker.shared.vnc.VncController}; they differ in what the guest
 * can draw.
 */
public enum Hypervisor {

    /** VMware Workstation: the guest gets a DirectX 11 GPU, so 3D games run. Installed by the user. */
    VMWARE("vmware", "VMware Workstation", true),
    /** QEMU on the Windows Hypervisor Platform: the guest draws in software, so it suits 2D and click games. */
    QEMU("qemu", "QEMU", false),
    UNKNOWN("unknown", "an unknown hypervisor", false);

    private final String id;
    private final String displayName;
    private final boolean gpu;

    Hypervisor(String id, String displayName, boolean gpu) {
        this.id = id;
        this.displayName = displayName;
        this.gpu = gpu;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether the guest gets a 3D-capable GPU. */
    public boolean suits3d() {
        return gpu;
    }

    /** The hypervisor whose {@link #id()} is {@code id}; {@link #UNKNOWN} for anything else. */
    public static Hypervisor fromId(String id) {
        for (Hypervisor h : values()) {
            if (h.id.equals(id)) return h;
        }
        return UNKNOWN;
    }

    /** The one to use here: VMware when it is installed, else QEMU when it is, else {@link #UNKNOWN}. */
    public static Hypervisor detect() {
        if (VmwareWorkstation.find().isPresent()) return VMWARE;
        if (Qemu.find().isPresent()) return QEMU;
        return UNKNOWN;
    }
}
