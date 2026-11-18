// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The protocol's framing against canned messages: ids, answers, errors, events, sessions. No browser. */
class CdpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A wire that keeps what is sent and answers nothing by itself. */
    private static final class Recorded implements Cdp.Wire {
        final List<String> sent = new ArrayList<>();
        boolean closed;

        @Override
        public CompletionStage<?> send(String text) {
            sent.add(text);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }

        JsonNode last() throws Exception {
            return JSON.readTree(sent.getLast());
        }
    }

    @Test
    void aCommandCarriesItsIdMethodAndParamsAndItsAnswerCompletesIt() throws Exception {
        Recorded wire = new Recorded();
        Cdp cdp = new Cdp(wire);

        CompletableFuture<JsonNode> answer = cdp.browser().send("Target.createTarget", Map.of("url", "about:blank"));
        JsonNode sent = wire.last();
        assertEquals("Target.createTarget", sent.path("method").asText());
        assertEquals("about:blank", sent.path("params").path("url").asText());
        assertFalse(sent.has("sessionId"));
        assertFalse(answer.isDone());

        cdp.receive("{\"id\":" + sent.path("id").asInt() + ",\"result\":{\"targetId\":\"T1\"}}");
        assertEquals("T1", answer.get().path("targetId").asText());
    }

    @Test
    void idsAreDistinctAndAnswersMayComeOutOfOrder() throws Exception {
        Recorded wire = new Recorded();
        Cdp cdp = new Cdp(wire);
        CompletableFuture<JsonNode> first = cdp.browser().send("A.one", Map.of());
        int firstId = wire.last().path("id").asInt();
        CompletableFuture<JsonNode> second = cdp.browser().send("A.two", Map.of());
        int secondId = wire.last().path("id").asInt();
        assertTrue(firstId != secondId);

        cdp.receive("{\"id\":" + secondId + ",\"result\":{\"n\":2}}");
        cdp.receive("{\"id\":" + firstId + ",\"result\":{\"n\":1}}");
        assertEquals(1, first.get().path("n").asInt());
        assertEquals(2, second.get().path("n").asInt());
    }

    @Test
    void anErrorAnswerFailsTheCommandWithChromiumsCodeAndMessage() throws Exception {
        Recorded wire = new Recorded();
        Cdp cdp = new Cdp(wire);
        CompletableFuture<JsonNode> answer = cdp.browser().send("DOM.getBoxModel", Map.of("backendNodeId", 7));
        cdp.receive("{\"id\":" + wire.last().path("id").asInt()
                + ",\"error\":{\"code\":-32000,\"message\":\"Could not compute box model.\"}}");

        ExecutionException failed = assertThrows(ExecutionException.class, answer::get);
        Cdp.CdpException cause = assertInstanceOf(Cdp.CdpException.class, failed.getCause());
        assertEquals(-32000, cause.code());
        assertTrue(cause.getMessage().contains("DOM.getBoxModel"), cause.getMessage());
        assertTrue(cause.getMessage().contains("Could not compute box model."), cause.getMessage());
    }

    @Test
    void attachingSendsFlatModeAndTheSessionsCommandsAndEventsCarryItsId() throws Exception {
        Recorded wire = new Recorded();
        // attach waits for its answer: this wire answers the attach as it is sent.
        Cdp[] holder = new Cdp[1];
        Cdp answering = new Cdp(new Cdp.Wire() {
            @Override
            public CompletionStage<?> send(String text) {
                wire.send(text);
                if (text.contains("Target.attachToTarget")) {
                    try {
                        holder[0].receive("{\"id\":" + wire.last().path("id").asInt()
                                + ",\"result\":{\"sessionId\":\"S9\"}}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                wire.close();
            }
        });
        holder[0] = answering;
        Cdp.Session session = answering.attach("T1");
        JsonNode attach = JSON.readTree(wire.sent.getFirst());
        assertEquals("Target.attachToTarget", attach.path("method").asText());
        assertTrue(attach.path("params").path("flatten").asBoolean());
        assertEquals("S9", session.id());

        session.send("Page.enable", Map.of());
        assertEquals("S9", wire.last().path("sessionId").asText());

        List<String> seen = new ArrayList<>();
        session.on("Page.loadEventFired", params -> seen.add("tab " + params.path("timestamp").asText()));
        answering.browser().on("Page.loadEventFired", params -> seen.add("browser"));
        answering.receive("{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":1.5},\"sessionId\":\"S9\"}");
        answering.receive("{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":2},\"sessionId\":\"OTHER\"}");
        assertEquals(List.of("tab 1.5"), seen);
        answering.close();
        assertTrue(wire.closed);
    }

    @Test
    void aListenerThatThrowsStopsNoOtherListener() {
        Cdp cdp = new Cdp(new Recorded());
        List<String> seen = new ArrayList<>();
        cdp.browser().on("Target.targetCreated", params -> {
            throw new IllegalStateException("boom");
        });
        cdp.browser().on("Target.targetCreated", params -> seen.add(params.path("targetInfo").path("type").asText()));
        cdp.receive("{\"method\":\"Target.targetCreated\",\"params\":{\"targetInfo\":{\"type\":\"page\"}}}");
        assertEquals(List.of("page"), seen);
    }

    @Test
    void closingFailsWhatWaitsAndRefusesWhatFollows() {
        Recorded wire = new Recorded();
        Cdp cdp = new Cdp(wire);
        CompletableFuture<JsonNode> waiting = cdp.browser().send("Page.navigate", Map.of("url", "x"));
        cdp.close();
        assertTrue(wire.closed);
        assertTrue(waiting.isCompletedExceptionally());
        assertThrows(Cdp.CdpException.class, () -> cdp.browser().call("Page.enable"));
    }

    @Test
    void aMessageThatCannotBeSentFailsItsCommandAtOnce() {
        Cdp cdp = new Cdp(new Cdp.Wire() {
            @Override
            public CompletionStage<?> send(String text) {
                return CompletableFuture.failedFuture(new IllegalStateException("socket broke"));
            }

            @Override
            public void close() {
            }
        });
        Cdp.CdpException failed = assertThrows(Cdp.CdpException.class, () -> cdp.browser().call("Page.enable"));
        assertTrue(failed.getMessage().contains("socket broke"), failed.getMessage());
    }

    @Test
    void forgettingASessionDropsOnlyItsListeners() {
        Cdp cdp = new Cdp(new Recorded());
        List<String> seen = new ArrayList<>();
        cdp.browser().on("Page.loadEventFired", params -> seen.add("browser"));
        cdp.forget("S1");
        cdp.receive("{\"method\":\"Page.loadEventFired\",\"params\":{}}");
        assertEquals(List.of("browser"), seen);
    }

    @Test
    void garbageAndUnknownIdsAreIgnored() {
        Cdp cdp = new Cdp(new Recorded());
        cdp.receive("not json");
        cdp.receive("{\"id\":999,\"result\":{}}");
        cdp.receive("{\"method\":\"Nobody.listens\",\"params\":{}}");
    }
}
