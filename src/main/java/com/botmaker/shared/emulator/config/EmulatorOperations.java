package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.AdbEndpoint;
import com.botmaker.shared.emulator.EmulatorInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The queue of what the Emulators window is doing. Operations on instances that share an ADB port (LDPlayer
 * and BlueStacks both take 5555) run one at a time, in the order given, since only one of them can be up;
 * others run side by side. Listeners hear each change on the queue's own notifier thread, never the caller's.
 */
public final class EmulatorOperations implements AutoCloseable {

    private final Map<String, ExecutorService> lanes = new ConcurrentHashMap<>();
    private final ExecutorService notifier = Executors.newSingleThreadExecutor(daemon("emulator-operations-notify"));
    private final List<Consumer<Operation.Snapshot>> listeners = new CopyOnWriteArrayList<>();
    private final Queue<Operation> operations = new ConcurrentLinkedQueue<>();
    private boolean closed;

    public void addListener(Consumer<Operation.Snapshot> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Operation.Snapshot> listener) {
        listeners.remove(listener);
    }

    /** Queues {@code body} on {@code instance}'s lane and answers it, queued; refused once the queue is closed. */
    public synchronized Operation submit(String title, EmulatorInstance instance, Operation.Body body) {
        if (closed) throw new IllegalStateException("The operations queue is closed.");
        Operation operation = new Operation(title, InstanceKey.of(instance), lane(instance), body);
        operations.add(operation);
        notifyAll(operation.snapshot());
        lanes.computeIfAbsent(operation.lane(), name -> Executors.newSingleThreadExecutor(daemon("emulator-lane-" + name)))
                .execute(() -> operation.run(this::notifyAll));
        return operation;
    }

    /** Every operation not cleared, newest last. */
    public List<Operation.Snapshot> all() {
        List<Operation.Snapshot> out = new ArrayList<>();
        operations.forEach(op -> out.add(op.snapshot()));
        return out;
    }

    /** Forgets the operations that ended. */
    public void clearEnded() {
        operations.removeIf(op -> op.snapshot().state().ended());
    }

    /** The lane: the loopback port, which products share; else the endpoint itself. */
    static String lane(EmulatorInstance instance) {
        return instance.adb() instanceof AdbEndpoint.Tcp(String host, int port) ? "port-" + port
                : "device-" + instance.endpoint();
    }

    private void notifyAll(Operation.Snapshot snapshot) {
        try {
            notifier.execute(() -> listeners.forEach(listener -> {
                try {
                    listener.accept(snapshot);
                } catch (RuntimeException e) {
                    // a listener's failure is its own
                }
            }));
        } catch (RejectedExecutionException e) {
            // closed: nobody listens any more, and a running operation still puts back what it changed
        }
    }

    /**
     * Cancels every operation; one running finishes putting back what it changed on its own lane, unheard. Nothing can
     * be submitted after.
     */
    @Override
    public synchronized void close() {
        closed = true;
        operations.forEach(Operation::cancel);
        lanes.values().forEach(ExecutorService::shutdown);
        notifier.shutdown();
    }

    /** Waits for every lane to finish what it holds; for tests and shutdown. */
    boolean awaitIdle(long millis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            if (operations.stream().allMatch(op -> op.snapshot().state().ended())) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
