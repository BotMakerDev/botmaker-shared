package com.botmaker.shared.ipc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** End-to-end loopback tests for {@link TelemetryServer} ↔ {@link TelemetryClient}. */
class TelemetryChannelTest {

    private static final TelemetryEvent.Target TARGET =
            new TelemetryEvent.Target("Game", 0, 0, 640, 480);

    @Test
    void framesDeliveredOverLoopback() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        try (TelemetryServer server = new TelemetryServer("secret", received::offer)) {
            try (TelemetryClient client = new TelemetryClient(server.port(), "secret")) {
                TelemetryEvent match = new TelemetryEvent.Match(
                        TARGET, null, new TelemetryEvent.Rect(1, 2, 3, 4), 0.8, true);
                TelemetryEvent click = new TelemetryEvent.Click(TARGET, 5, 6, 1);
                client.send(match);
                client.send(click);

                assertEquals(match, received.poll(3, TimeUnit.SECONDS));
                assertEquals(click, received.poll(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void aQuestionIsAnsweredOnTheSameSocket() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        try (TelemetryServer server = new TelemetryServer("secret", received::offer);
             TelemetryClient client = new TelemetryClient(server.port(), "secret")) {
            CompletableFuture<String> mode = client.ask("choice", "Mode?", List.of("farm", "fight"), -1);
            CompletableFuture<String> name = client.ask("text", "Name?", List.of(), -1);
            TelemetryEvent.Ask first = (TelemetryEvent.Ask) received.poll(3, TimeUnit.SECONDS);
            TelemetryEvent.Ask second = (TelemetryEvent.Ask) received.poll(3, TimeUnit.SECONDS);
            assertEquals("Mode?", first.prompt());
            assertEquals(List.of("farm", "fight"), first.choices());

            // Answered out of order, each by its id; a cancel answers null.
            assertTrue(server.reply(new TelemetryEvent.Answer(second.id(), null)));
            assertTrue(server.reply(new TelemetryEvent.Answer(first.id(), "fight")));
            assertEquals("fight", mode.get(3, TimeUnit.SECONDS));
            assertNull(name.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void anOpenQuestionFailsWhenTheHostGoesAway() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        TelemetryServer server = new TelemetryServer("secret", received::offer);
        try (TelemetryClient client = new TelemetryClient(server.port(), "secret")) {
            CompletableFuture<String> answer = client.ask("text", "Name?", List.of(), -1);
            received.poll(3, TimeUnit.SECONDS);
            server.close();
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> answer.get(5, TimeUnit.SECONDS));
            assertTrue(failed.getCause() instanceof java.io.IOException, failed.toString());
        }
        assertFalse(new TelemetryServer("x", e -> {}).reply(new TelemetryEvent.Answer(1, "nobody connected")));
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        try (TelemetryServer server = new TelemetryServer("expected", received::offer)) {
            try (TelemetryClient client = new TelemetryClient(server.port(), "wrong")) {
                client.send(new TelemetryEvent.Click(TARGET, 1, 1, 1));
                // Server closes the connection after the bad handshake; nothing should arrive.
                assertNull(received.poll(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void sendNeverBlocksWhenNoServer() {
        // No server listening: the writer thread stalls on connect, so send() must drop, not block.
        try (TelemetryClient client = new TelemetryClient(1, "token", 2)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 10_000; i++) {
                    client.send(new TelemetryEvent.Click(TARGET, i, i, 1));
                }
            });
        }
    }
}
