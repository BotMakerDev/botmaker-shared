package com.botmaker.shared.ipc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accept loop's survival contract: <b>a consumer that throws must not take the channel with it</b>.
 *
 * <p>{@code onEvent} is supplied by the caller — in Studio it republishes on the {@code EventBus}, so any
 * handler subscribed there can throw. The accept loop used to catch {@link java.io.IOException} and
 * {@link TelemetryFrame.FrameFormatException} and nothing else, so an unchecked exception out of
 * {@code onEvent} unwound the whole loop and the run went permanently silent with no error anywhere: the
 * daemon accept thread was gone while {@code close()} and {@code port()} kept answering normally. That is
 * <b>B6</b>, fixed in Phase 4 (S7); these tests are its gate and were written {@code @Disabled} against the
 * commit that logged it, verified red there, and enabled by the fix.
 */
class TelemetryServerResilienceTest {

    private static final TelemetryEvent.Target TARGET =
            new TelemetryEvent.Target("Game", 0, 0, 640, 480);

    private static TelemetryEvent.Click click(int n) {
        return new TelemetryEvent.Click(TARGET, n, n, 1);
    }

    @Test
    void consumerThatThrowsDoesNotKillTheChannel() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        AtomicInteger seen = new AtomicInteger();
        // Throws on the first event only; every later one must still arrive.
        java.util.function.Consumer<TelemetryEvent> hostile = event -> {
            if (seen.incrementAndGet() == 1) throw new IllegalStateException("subscriber blew up");
            received.offer(event);
        };

        try (TelemetryServer server = new TelemetryServer("secret", hostile)) {
            try (TelemetryClient client = new TelemetryClient(server.port(), "secret")) {
                client.send(click(1));
                client.send(click(2));
                client.send(click(3));

                assertEquals(click(2), received.poll(5, TimeUnit.SECONDS),
                        "the event after a throwing consumer never arrived — the accept loop died with it");
                assertEquals(click(3), received.poll(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void everyConsumerThrowStillLeavesTheServerAcceptingReconnects() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        java.util.function.Consumer<TelemetryEvent> alwaysThrows = event -> {
            delivered.incrementAndGet();
            throw new RuntimeException("every time");
        };

        try (TelemetryServer server = new TelemetryServer("secret", alwaysThrows)) {
            try (TelemetryClient first = new TelemetryClient(server.port(), "secret")) {
                first.send(click(1));
                awaitAtLeast(delivered, 1);
            }
            // A bot that reconnects mid-run must still be served: the accept loop has to be alive.
            try (TelemetryClient second = new TelemetryClient(server.port(), "secret")) {
                second.send(click(2));
                awaitAtLeast(delivered, 2);
            }
        }
        assertTrue(delivered.get() >= 2, "the server stopped accepting after a consumer threw");
    }

    /**
     * Surviving the listener is half the fix; the other half is not doing it silently. Three throws must
     * produce exactly one notice — a per-event error would be as unusable as no error at all, since a handler
     * that throws once usually throws on everything.
     */
    @Test
    void aListenerFaultIsReportedOnceThroughOnError() throws Exception {
        BlockingQueue<String> errors = new ArrayBlockingQueue<>(8);
        AtomicInteger delivered = new AtomicInteger();
        java.util.function.Consumer<TelemetryEvent> alwaysThrows = event -> {
            delivered.incrementAndGet();
            throw new IllegalStateException("subscriber blew up");
        };

        try (TelemetryServer server = new TelemetryServer("secret", alwaysThrows, errors::offer)) {
            try (TelemetryClient client = new TelemetryClient(server.port(), "secret")) {
                client.send(click(1));
                client.send(click(2));
                client.send(click(3));
                awaitAtLeast(delivered, 3);
            }
        }
        String reported = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(reported, "a listener that threw on every event never reached onError");
        assertTrue(reported.contains("subscriber blew up"),
                "the notice must name the cause, not just say something failed: " + reported);
        assertTrue(errors.isEmpty(), "the listener fault was reported more than once: " + errors);
    }

    @Test
    void anUndecodableFrameIsReportedOnceAndTheStreamContinues() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        BlockingQueue<String> errors = new ArrayBlockingQueue<>(8);

        try (TelemetryServer server = new TelemetryServer("secret", received::offer, errors::offer)) {
            try (TelemetryClient client = new TelemetryClient(server.port(), "secret")) {
                client.send(click(1));
                assertNotNull(received.poll(5, TimeUnit.SECONDS));
            }
        }
        // No skew here, so nothing to report — the one-shot sink stays silent on a healthy stream.
        assertTrue(errors.isEmpty(), "a well-formed stream reported an error: " + errors);
    }

    private static void awaitAtLeast(AtomicInteger counter, int target) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counter.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(counter.get() >= target, "expected at least " + target + " deliveries, got " + counter.get());
    }
}
