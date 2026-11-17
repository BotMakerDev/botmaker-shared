// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import com.botmaker.shared.host.MemoryBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What an instance asks of the memory budget. */
class InstanceMemoryTest {

    @Test
    void anInstanceCommitsAllItIsGivenAndUsesAboutHalf() {
        assertEquals(new MemoryBudget.Need("Light", 2560, 1536), InstanceMemory.need("Light", LightProfile.MEMORY_MB,
                30_000));
        assertEquals(new MemoryBudget.Need("Unread", 4608, 2560), InstanceMemory.need("Unread", 0, 30_000),
                "a memory not read is taken as 4 GB");
    }

    @Test
    void anUnreadMemoryIsNeverCountedPastWhatThePcCanGive() {
        assertEquals(new MemoryBudget.Need("Unread", 3072, 2560), InstanceMemory.need("Unread", 0, 3072),
                "a 4 GB PC with a 1 GB reserve gives an unread instance its 3 GB");
        assertEquals(4608, InstanceMemory.need("Read", 4096, 3072).commitsMb(), "a read memory is not bounded");
    }
}
