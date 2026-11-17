// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import com.botmaker.shared.host.MemoryBudget;

/**
 * What an emulator instance asks of this PC's {@link MemoryBudget} (spec
 * {@code 2026-11-14-end-to-end-instances-design.md} § The RAM budget): it commits all the memory its Android is given
 * plus its emulator's own ({@link #takes}), and keeps in physical memory about half of it, as Android touches no more,
 * plus its emulator's ({@link #uses}).
 */
public final class InstanceMemory {

    /** What an emulator takes beside its Android's memory. */
    public static final long OVERHEAD_MB = 512;
    /** What an instance whose memory was not read is taken to be given. */
    public static final int UNREAD_MB = 4096;

    private InstanceMemory() {}

    /** What an instance of {@code configuredMb} commits; 0 or less is a memory not read. */
    public static long takes(int configuredMb) {
        return (configuredMb > 0 ? configuredMb : UNREAD_MB) + OVERHEAD_MB;
    }

    /** What an instance of {@code configuredMb} is expected to keep in physical memory once up. */
    public static long uses(int configuredMb) {
        return (configuredMb > 0 ? configuredMb : UNREAD_MB) / 2 + OVERHEAD_MB;
    }

    /** What instance {@code name} of {@code configuredMb} asks of {@code budget}. */
    public static MemoryBudget.Need need(String name, int configuredMb, MemoryBudget budget) {
        return need(name, configuredMb, budget.mostMb());
    }

    /**
     * {@link #takes} and {@link #uses}, except that one whose memory was not read is never counted as more than
     * {@code mostMb}, so on a small PC it is neither refused nor kept waiting for good.
     */
    static MemoryBudget.Need need(String name, int configuredMb, long mostMb) {
        long commits = configuredMb > 0 ? takes(configuredMb)
                : Math.min(takes(0), Math.max(OVERHEAD_MB, mostMb));
        return new MemoryBudget.Need(name, commits, Math.min(uses(configuredMb), commits));
    }
}
