package com.botmaker.shared.vm;

import java.nio.file.Path;
import java.util.List;

/**
 * What a game VM is made of: its name and folder, its size, the Windows disc it installs from, the further
 * discs it carries (the answer disc, then the guest tools), and the loopback port its screen is served on.
 *
 * @param discs further CD images in drive order after the Windows one
 */
public record VmSpec(String name, Path folder, int cpus, int memoryMb, int diskGb, Path windowsIso,
                     List<Path> discs, int vncPort) {

    /** Windows 11's own minimum is 4 GB; the guest is unusable below it. */
    public static final int MIN_MEMORY_MB = 4096;
    public static final int MAX_DEFAULT_MEMORY_MB = 8192;
    public static final int DEFAULT_DISK_GB = 80;
    public static final int MAX_DEFAULT_CPUS = 4;

    public VmSpec {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,40}")) {
            throw new IllegalArgumentException("A VM name is letters, digits, spaces, - and _: " + name);
        }
        if (cpus < 1 || memoryMb < MIN_MEMORY_MB || diskGb < 64) {
            throw new IllegalArgumentException("Windows 11 needs 1 core, 4 GB of memory and a 64 GB disk at least.");
        }
        if (vncPort < 1 || vncPort > 65535) throw new IllegalArgumentException("No such port: " + vncPort);
        discs = List.copyOf(discs);
    }

    /**
     * The size a host gives by default: half its memory up to 8 GB (4 GB at least), half its cores up to 4, and
     * an 80 GB disk that grows as it fills.
     */
    public static VmSpec sized(String name, Path folder, long hostMemoryMb, int hostCpus, Path windowsIso,
                               List<Path> discs, int vncPort) {
        int memory = (int) Math.clamp(hostMemoryMb / 2 / 1024 * 1024, MIN_MEMORY_MB, MAX_DEFAULT_MEMORY_MB);
        int cpus = Math.clamp(hostCpus / 2, 1, MAX_DEFAULT_CPUS);
        return new VmSpec(name, folder, cpus, memory, DEFAULT_DISK_GB, windowsIso, discs, vncPort);
    }
}
