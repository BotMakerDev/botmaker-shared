package com.botmaker.shared.emulator.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The budget over a made-up PC: 13.9 GB, free memory as each test sets it. */
class RamBudgetTest {

    private static final long TOTAL_MB = 14_234;
    private static final int LIGHT = LightProfile.MEMORY_MB; // takes 2.5 GB

    @Test
    void roomForNoneOneAndThree() {
        AtomicLong free = new AtomicLong(5 * 1024 + 2000);
        RamBudget budget = new RamBudget(free::get, TOTAL_MB, d -> {});
        assertEquals(0, budget.room(LIGHT), "2 GB over the reserve holds no 2.5 GB instance");
        free.set(5 * 1024 + 3000);
        assertEquals(1, budget.room(LIGHT));
        free.set(5 * 1024 + 8000);
        assertEquals(3, budget.room(LIGHT));
        assertEquals(1, budget.room(0), "a memory not read is taken as 4 GB");
    }

    @Test
    void anInstanceStartingKeepsItsRoomUntilItIsUp() throws Exception {
        RamBudget budget = new RamBudget(() -> 5 * 1024 + 6000, TOTAL_MB, d -> {});
        Context context = new Context();
        RamBudget.Hold first = budget.await(LIGHT, context);
        RamBudget.Hold second = budget.await(LIGHT, context);
        assertEquals(0, budget.room(LIGHT), "two starting take the 5 GB the PC has free");
        second.close();
        assertEquals(1, budget.room(LIGHT));
        first.close();
        assertEquals(2, budget.room(LIGHT));
    }

    @Test
    void aStartWaitsForMemoryAndSaysSo() throws Exception {
        AtomicLong free = new AtomicLong(5 * 1024 + 1000);
        List<Long> waits = new ArrayList<>();
        RamBudget budget = new RamBudget(free::get, TOTAL_MB, d -> {
            waits.add(d.toSeconds());
            free.addAndGet(1000);
        });
        Context context = new Context();
        assertNotNull(budget.await(LIGHT, context));
        assertEquals(2, waits.size(), "free memory grew to 3 GB over two looks");
        assertEquals("Waiting for memory: 6 GB free, 2.5 GB needed beside the 5 GB kept for Windows and BotMaker.",
                context.steps.getFirst());
    }

    @Test
    void aCancelledWaitStartsNothing() throws Exception {
        Context context = new Context();
        AtomicLong free = new AtomicLong(1000);
        RamBudget budget = new RamBudget(free::get, TOTAL_MB, d -> context.cancelled.set(true));
        assertNull(budget.await(LIGHT, context));
        free.set(5 * 1024 + 3000);
        assertEquals(1, budget.room(LIGHT), "the cancelled start holds no room");
    }

    @Test
    void anInstanceLargerThanThePcCanGiveNeverWaits() {
        RamBudget budget = new RamBudget(() -> 1000, TOTAL_MB, d -> {
            throw new AssertionError("never waits");
        });
        RamBudget.NoRoom refused = assertThrows(RamBudget.NoRoom.class, () -> budget.await(16_384, new Context()));
        assertTrue(refused.getMessage().contains("never fits"), refused.getMessage());
        assertNull(budget.never(LIGHT));
    }

    @Test
    void anUnreadMemoryIsNeverRefusedNorKeptWaitingOnASmallPc() throws Exception {
        long eightGb = 8 * 1024;
        RamBudget budget = new RamBudget(() -> eightGb, eightGb, d -> {
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
