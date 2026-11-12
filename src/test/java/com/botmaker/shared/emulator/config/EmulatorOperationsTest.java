package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorOperationsTest {

    private static final EmulatorInstance LD = instance(PlatformId.LDPLAYER, 5555);
    private static final EmulatorInstance BLUESTACKS = instance(PlatformId.BLUESTACKS, 5555);
    private static final EmulatorInstance MUMU = instance(PlatformId.MUMU, 16384);

    @Test
    void instancesSharingAPortRunOneAtATime() throws Exception {
        try (EmulatorOperations queue = new EmulatorOperations()) {
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger most = new AtomicInteger();
            Operation.Body body = context -> {
                most.accumulateAndGet(inside.incrementAndGet(), Math::max);
                Thread.sleep(80);
                inside.decrementAndGet();
                return null;
            };
            queue.submit("a", LD, body);
            queue.submit("b", BLUESTACKS, body);
            assertTrue(queue.awaitIdle(5_000));
            assertEquals(1, most.get(), "5555's two operations never overlap");
        }
    }

    @Test
    void otherPortsRunSideBySide() throws Exception {
        try (EmulatorOperations queue = new EmulatorOperations()) {
            CountDownLatch both = new CountDownLatch(2);
            Operation.Body body = context -> {
                both.countDown();
                return both.await(5, TimeUnit.SECONDS) ? null : "alone";
            };
            Operation a = queue.submit("a", LD, body);
            Operation b = queue.submit("b", MUMU, body);
            assertTrue(queue.awaitIdle(10_000));
            assertEquals(Operation.State.DONE, a.snapshot().state());
            assertEquals(Operation.State.DONE, b.snapshot().state());
        }
    }

    @Test
    void aQueuedOperationCancelledNeverStarts() throws Exception {
        try (EmulatorOperations queue = new EmulatorOperations()) {
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger ran = new AtomicInteger();
            queue.submit("first", LD, context -> release.await(5, TimeUnit.SECONDS) ? null : "timeout");
            Operation second = queue.submit("second", BLUESTACKS, context -> {
                ran.incrementAndGet();
                return null;
            });
            second.cancel();
            release.countDown();
            assertTrue(queue.awaitIdle(5_000));
            assertEquals(0, ran.get());
            assertEquals(Operation.State.CANCELLED, second.snapshot().state());
        }
    }

    @Test
    void stepsFailuresAndListenersOnTheNotifierThread() throws Exception {
        try (EmulatorOperations queue = new EmulatorOperations()) {
            List<Operation.Snapshot> heard = new CopyOnWriteArrayList<>();
            Set<String> threads = ConcurrentHashMap.newKeySet();
            CountDownLatch ended = new CountDownLatch(1);
            queue.addListener(snapshot -> {
                heard.add(snapshot);
                threads.add(Thread.currentThread().getName());
                if (snapshot.state().ended()) ended.countDown();
            });
            Operation op = queue.submit("apply", MUMU, context -> {
                context.step("Writing");
                context.progress(0.5);
                return "MuMu refused";
            });
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            assertEquals(Set.of("emulator-operations-notify"), threads);
            assertTrue(heard.stream().anyMatch(s -> "Writing".equals(s.step())));
            Operation.Snapshot end = op.snapshot();
            assertEquals(Operation.State.FAILED, end.state());
            assertEquals("MuMu refused", end.outcome());
            assertEquals(0.5, end.progress());
            queue.clearEnded();
            assertTrue(queue.all().isEmpty());
            assertFalse(heard.isEmpty());
        }
    }

    private static EmulatorInstance instance(PlatformId platform, int port) {
        return new EmulatorInstance(platform, platform.displayName(), "127.0.0.1", port);
    }
}
