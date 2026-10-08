package com.botmaker.shared.vm;

import java.lang.management.ManagementFactory;

/** How big a game VM is: its cores, memory and disk (which grows as it fills, up to this). */
public record VmSize(int cpus, int memoryMb, int diskGb) {

    /** Windows 11's own minimum is 4 GB; the guest is unusable below it. */
    public static final int MIN_MEMORY_MB = 4096;
    public static final int MIN_DISK_GB = 64;
    public static final int MAX_DEFAULT_MEMORY_MB = 8192;
    public static final int DEFAULT_DISK_GB = 80;
    public static final int MAX_DEFAULT_CPUS = 4;

    public VmSize {
        if (cpus < 1 || memoryMb < MIN_MEMORY_MB || diskGb < MIN_DISK_GB) {
            throw new IllegalArgumentException("Windows 11 needs 1 core, 4 GB of memory and a 64 GB disk at least.");
        }
    }

    /**
     * The size a host gives by default: half its memory up to 8 GB (4 GB at least), half its cores up to 4, and
     * an 80 GB disk.
     */
    public static VmSize forHost(long hostMemoryMb, int hostCpus) {
        int memory = (int) Math.clamp(hostMemoryMb / 2 / 1024 * 1024, MIN_MEMORY_MB, MAX_DEFAULT_MEMORY_MB);
        return new VmSize(Math.clamp(hostCpus / 2, 1, MAX_DEFAULT_CPUS), memory, DEFAULT_DISK_GB);
    }

    public static VmSize forThisHost() {
        return forHost(hostMemoryMb(), Runtime.getRuntime().availableProcessors());
    }

    /** This computer's physical memory. */
    public static long hostMemoryMb() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getTotalMemorySize() / (1024 * 1024);
        }
        return MIN_MEMORY_MB * 2L;
    }
}
