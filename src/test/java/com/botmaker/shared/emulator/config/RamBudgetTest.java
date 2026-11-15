package com.botmaker.shared.emulator.config;

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
 * A light instance uses 1.5 GB and commits 2.5 GB.
 */
class RamBudgetTest {

    private static final long TOTAL_MB = 14_234;
    private static final long RESERVE = 1423;
    private static final long PLENTY = 30_000;
    private static final int LIGHT = LightProfile.MEMORY_MB;

    @Test
    void theReserveIsATenthOfThePcFromOneToTwoGb() {
        assertEquals(RESERVE, budget(() -> 0, () -> 0).reserveMb());
        assertEquals(1024, new RamBudget(() -> 0, () -> 0, 4096, d -> {}).reserveMb());
        assertEquals(2048, new RamBudget(() -> 0, () -> 0, 65_536, d -> {}).reserveMb());
    }

    @Test
    void freeMemoryHoldsWhatAnInstanceUsesNotAllItIsGiven() {
        AtomicLong free = new AtomicLong(RESERVE + 1400);
        RamBudget budget = budget(free::get, () -> PLENTY);
        assertEquals(0, budget.room(LIGHT), "1.4 GB over the reserve holds no instance using 1.5 GB");
        free.set(RESERVE + 1536);
        assertEquals(1, budget.room(LIGHT));
        free.set(4900);
        assertEquals(2, budget.room(LIGHT), "this PC's 4.9 GB free holds two light instances");
        assertEquals(1, budget.room(0), "a memory not read is taken as 4 GB, using 2.5 GB");
    }

    @Test
    void whatThePcCanCommitHoldsAllAnInstanceIsGiven() {
        AtomicLong commit = new AtomicLong(RESERVE + 2000);
        RamBudget budget = budget(() -> PLENTY, commit::get);
        assertEquals(0, budget.room(LIGHT), "2 GB left to commit cannot start a VM given 2.5 GB");
        commit.set(RESERVE + 5120);
        assertEquals(2, budget.room(LIGHT));
    }

    @Test
    void anInstanceStartingKeepsItsRoomUntilItIsUp() throws Exception {
        RamBudget budget = budget(() -> RESERVE + 3072, () -> PLENTY);
        Context context = new Context();
        RamBudget.Hold first = budget.await(LIGHT, context);
        RamBudget.Hold second = budget.await(LIGHT, context);
        assertEquals(0, budget.room(LIGHT), "two starting use the 3 GB the PC has free");
        second.close();
        assertEquals(1, budget.room(LIGHT));
        first.close();
        assertEquals(2, budget.room(LIGHT));
    }

    @Test
    void aStartWaitsForMemoryAndSaysWhichMeasureHoldsIt() throws Exception {
        AtomicLong free = new AtomicLong(RESERVE + 500);
        List<Long> waits = new ArrayList<>();
        RamBudget budget = new RamBudget(free::get, () -> PLENTY, TOTAL_MB, d -> {
            waits.add(d.toSeconds());
            free.addAndGet(600);
        });
        Context context = new Context();
        assertNotNull(budget.await(LIGHT, context));
        assertEquals(2, waits.size(), "free memory grew past 1.5 GB over two looks");
        assertEquals("Waiting for memory: 1.9 GB free, 1.5 GB needed beside the 1.4 GB kept for Windows and BotMaker.",
                context.steps.getFirst());

        RamBudget committing = budget(() -> PLENTY, () -> RESERVE + 1000);
        assertEquals("Waiting for memory: 2.4 GB left to commit, 2.5 GB needed beside the 1.4 GB kept for Windows and "
                + "BotMaker.", committing.waiting(LIGHT));
    }

    @Test
    void aCancelledWaitStartsNothing() throws Exception {
        Context context = new Context();
        AtomicLong free = new AtomicLong(1000);
        RamBudget budget = new RamBudget(free::get, () -> PLENTY, TOTAL_MB, d -> context.cancelled.set(true));
        assertNull(budget.await(LIGHT, context));
        free.set(RESERVE + 1536);
        assertEquals(1, budget.room(LIGHT), "the cancelled start holds no room");
    }

    @Test
    void anInstanceLargerThanThePcCanGiveNeverWaits() {
        RamBudget budget = new RamBudget(() -> 1000, () -> PLENTY, TOTAL_MB, d -> {
            throw new AssertionError("never waits");
        });
        RamBudget.NoRoom refused = assertThrows(RamBudget.NoRoom.class, () -> budget.await(16_384, new Context()));
        assertTrue(refused.getMessage().contains("never fits"), refused.getMessage());
        assertNull(budget.never(LIGHT));
    }

    @Test
    void anUnreadMemoryIsNeverRefusedNorKeptWaitingOnASmallPc() throws Exception {
        long fourGb = 4 * 1024;
        RamBudget budget = new RamBudget(() -> fourGb, () -> fourGb, fourGb, d -> {
            throw new AssertionError("the whole PC free is room for it");
        });
        assertNull(budget.never(0), "an unread memory is not refused");
        assertNotNull(budget.await(0, new Context()));
        assertNotNull(budget.never(4096), "a read 4 GB still never fits in 3 GB");
    }

    @Test
    void gigabytesAreSaidToATenth() {
        assertEquals("5 GB", RamBudget.gb(5 * 1024));
        assertEquals("2.5 GB", RamBudget.gb(2560));
        assertEquals("1.1 GB", RamBudget.gb(1126));
    }

    private static RamBudget budget(LongSupplier free, LongSupplier commit) {
        return new RamBudget(free, commit, TOTAL_MB, d -> {});
    }

    private static final class Context implements Operation.Context {
        final List<String> steps = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void step(String words) {
            steps.add(words);
        }

        @Override
        public void progress(double fraction) {}

        @Override
        public void needsYou(String why) {}

        @Override
        public void resumed() {}

        @Override
        public boolean cancelled() {
            return cancelled.get();
        }
    }
}
