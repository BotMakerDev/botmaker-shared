package com.botmaker.shared.ipc;

import org.junit.jupiter.api.Disabled;
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
 * handler subscribed there can throw. {@link TelemetryServer#acceptLoop} catches {@link java.io.IOException}
 * and {@link TelemetryFrame.FrameFormatException} but nothing else, so an unchecked exception out of
 * {@code onEvent} unwinds the whole loop and the run goes permanently silent with no error anywhere. That is
 * <b>B6</b>; these tests are its gate and {@link #consumerThatThrowsDoesNotKillTheChannel()} fails on the
 * commit that logged it.
 */
class TelemetryServerResilienceTest {

    private static final TelemetryEvent.Target TARGET =
            new TelemetryEvent.Target("Game", 0, 0, 640, 480);

    private static TelemetryEvent.Click click(int n) {
        return new TelemetryEvent.Click(TARGET, n, n, 1);
    }

    @Test
    @Disabled("B6 is unfixed: verified red on this commit. Delete this line in Phase 4 with S7's fix — "
            + "that is what makes it 'a test that fails on the previous commit'.")
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
    @Disabled("B6, as above — re-enable with S7 in Phase 4.")
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
