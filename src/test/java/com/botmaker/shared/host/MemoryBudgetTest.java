// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.host;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget over a made-up PC: 13.9 GB (a 1.4 GB reserve), free memory and what it can commit as each test sets them.
 * An instance commits 2.5 GB and uses 1.5 GB; a display, some other starter, commits 1 GB and uses 0.5 GB.
 */
class MemoryBudgetTest {

    private static final long TOTAL_MB = 14_234;
    private static final long RESERVE = 1423;
    private static final long PLENTY = 30_000;
    private static final MemoryBudget.Need INSTANCE = new MemoryBudget.Need("Instance 3", 2560, 1536);
    private static final MemoryBudget.Need DISPLAY = new MemoryBudget.Need("A private display", 1024, 512);

    @Test
    void theReserveIsATenthOfThePcFromOneToTwoGb() {
        assertEquals(RESERVE, budget(() -> 0, () -> 0).reserveMb());
        assertEquals(1024, new MemoryBudget(() -> 0, () -> 0, 4096, d -> {}).reserveMb());
        assertEquals(2048, new MemoryBudget(() -> 0, () -> 0, 65_536, d -> {}).reserveMb());
        assertEquals(TOTAL_MB - RESERVE, budget(() -> 0, () -> 0).mostMb());
    }

    @Test
    void whatThePcCanCommitHoldsAllAStartCommits() {
        AtomicLong commit = new AtomicLong(RESERVE + 2000);
        MemoryBudget budget = budget(() -> PLENTY, commit::get);
        assertEquals(0, budget.room(INSTANCE), "2 GB left to commit cannot start a VM committing 2.5 GB");
        commit.set(RESERVE + 5120);
        assertEquals(2, budget.room(INSTANCE));
    }

    @Test
    void shortPhysicalMemoryButEnoughCommitStartsAndSaysThePcMayPage() throws Exception {
        MemoryBudget budget = budget(() -> RESERVE + 500, () -> PLENTY);
        assertTrue(budget.room(INSTANCE) >= 1, "free physical memory holds no start back");
        Context context = new Context();
        assertNotNull(budget.await(INSTANCE, context.waiter()));
        assertEquals(List.of("Instance 3 starts with 1.9 GB of memory free, short of the 1.5 GB it uses beside the "
                + "1.4 GB kept for Windows and BotMaker: the PC may page and slow down."), context.steps);

        Context roomy = new Context();
        assertNotNull(budget(() -> PLENTY, () -> PLENTY).await(INSTANCE, roomy.waiter()));
        assertEquals(List.of(), roomy.steps, "a start with room says nothing");
    }

    @Test
    void shortCommitWaitsAndSaysSo() throws Exception {
        AtomicLong commit = new AtomicLong(RESERVE + 1000);
        List<Long> waits = new ArrayList<>();
        MemoryBudget budget = new MemoryBudget(() -> PLENTY, commit::get, TOTAL_MB, d -> {
            waits.add(d.toSeconds());
            commit.addAndGet(1000);
        });
        Context context = new Context();
        assertNotNull(budget.await(INSTANCE, context.waiter()));
        assertEquals(2, waits.size(), "what it can commit grew past 2.5 GB over two looks");
        assertEquals("Waiting for memory to start Instance 3: 1 GB left to commit, 2.5 GB needed, beside the 1.4 GB "
                + "kept for Windows and BotMaker.", context.steps.getFirst());
    }

    @Test
    void aStartKeepsItsRoomUntilItIsUp() throws Exception {
        MemoryBudget budget = budget(() -> PLENTY, () -> RESERVE + 5120);
        Context context = new Context();
        MemoryBudget.Hold first = budget.await(INSTANCE, context.waiter());
        MemoryBudget.Hold second = budget.await(INSTANCE, context.waiter());
        assertEquals(0, budget.room(INSTANCE), "two starting commit the 5 GB the PC has left");
        second.close();
        assertEquals(1, budget.room(INSTANCE));
        first.close();
        assertEquals(2, budget.room(INSTANCE));
    }

    @Test
    void aNonEmulatorStartersHoldCounts() throws Exception {
        MemoryBudget budget = budget(() -> PLENTY, () -> RESERVE + 3072);
        assertEquals(1, budget.room(INSTANCE));
        MemoryBudget.Hold display = budget.await(DISPLAY, new Context().waiter());
        assertEquals(0, budget.room(INSTANCE), "the display's 1 GB leaves 2 GB, short of the instance's 2.5 GB");
        assertEquals("Waiting for memory to start Instance 3: 2 GB left to commit, 2.5 GB needed, beside the 1.4 GB "
                + "kept for Windows and BotMaker and what A private display takes as it starts.",
                budget.waiting(INSTANCE));
        display.close();
        assertEquals(1, budget.room(INSTANCE));
    }

    @Test
    void aCancelledWaitStartsNothing() throws Exception {
        Context context = new Context();
        AtomicLong commit = new AtomicLong(1000);
        MemoryBudget budget = new MemoryBudget(() -> PLENTY, commit::get, TOTAL_MB, d -> context.cancelled.set(true));
        assertNull(budget.await(INSTANCE, context.waiter()));
        commit.set(RESERVE + 2560);
        assertEquals(1, budget.room(INSTANCE), "the cancelled start holds no room");
    }

    @Test
    void aStartLargerThanThePcCanGiveNeverWaits() {
        MemoryBudget budget = new MemoryBudget(() -> PLENTY, () -> 1000, TOTAL_MB, d -> {
            throw new AssertionError("never waits");
        });
        MemoryBudget.Need huge = new MemoryBudget.Need("Instance 9", 16_896, 8_704);
        MemoryBudget.NoRoom refused = assertThrows(MemoryBudget.NoRoom.class,
                () -> budget.await(huge, new Context().waiter()));
        assertTrue(refused.getMessage().startsWith("Instance 9 needs 16.5 GB and never fits"), refused.getMessage());
        assertNull(budget.never(INSTANCE));
    }

    @Test
    void thePageFileLetsAStartLargerThanThePcsMemoryFit() {
        MemoryBudget budget = new MemoryBudget(() -> PLENTY, () -> PLENTY, TOTAL_MB, 32_768, d -> {});
        assertNull(budget.never(new MemoryBudget.Need("Instance 9", 16_896, 8_704)));
        assertEquals(32_768 - RESERVE, budget.mostMb());
    }

    @Test
    void aHoldIsGivenBackWhenSayingThePagingFails() {
        MemoryBudget budget = budget(() -> 0, () -> RESERVE + 2560);
        MemoryBudget.Waiter broken = new MemoryBudget.Waiter(words -> {
            throw new IllegalStateException("the window is gone");
        }, () -> false);
        assertThrows(IllegalStateException.class, () -> budget.await(INSTANCE, broken));
        assertEquals(1, budget.room(INSTANCE), "no hold is left behind");
    }

    @Test
    void gigabytesAreSaidToATenth() {
        assertEquals("5 GB", MemoryBudget.gb(5 * 1024));
        assertEquals("2.5 GB", MemoryBudget.gb(2560));
        assertEquals("1.1 GB", MemoryBudget.gb(1126));
    }

    private static MemoryBudget budget(LongSupplier free, LongSupplier commit) {
        return new MemoryBudget(free, commit, TOTAL_MB, d -> {});
    }

    private static final class Context {
        final List<String> steps = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();

        MemoryBudget.Waiter waiter() {
            return new MemoryBudget.Waiter(steps::add, cancelled::get);
        }
    }
}
