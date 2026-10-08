package com.botmaker.shared.vm;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a hypervisor runs for a game VM: its name and folder, its size, the discs in its drives, and the
 * loopback port its screen is served on.
 *
 * @param windowsIso the Windows disc while Windows installs; {@code null} once it is installed
 * @param discs      further CD images in drive order after the Windows one
 */
public record VmSpec(String name, Path folder, VmSize size, Path windowsIso, List<Path> discs, int vncPort) {

    public VmSpec {
        requireName(name);
        Objects.requireNonNull(size, "size");
        if (vncPort < 1 || vncPort > 65535) throw new IllegalArgumentException("No such port: " + vncPort);
        discs = List.copyOf(discs);
    }

    /** Throws unless {@code name} can name a VM, and its folder: letters, digits, spaces, {@code -} and {@code _}. */
    public static String requireName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,40}")) {
            throw new IllegalArgumentException("A VM name is letters, digits, spaces, - and _: " + name);
        }
        return name;
    }
}
