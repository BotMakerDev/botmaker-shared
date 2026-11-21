// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.PlaywrightException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * <b>A page's DevTools commands and events</b>, for what Playwright has no call of its own for (the accessibility
 * tree, a node's box): Playwright's {@link CDPSession} on the page, Jackson in and out, every call made on the
 * {@link BrowserThread}.
 */
public final class PageCdp {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** How long a command may go unanswered. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    /** A command the browser answered with an error, or could not answer: the page went, or the node did. */
    public static final class CdpException extends RuntimeException {
        CdpException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final BrowserThread thread;
    private final CDPSession session;

    PageCdp(BrowserThread thread, CDPSession session) {
        this.thread = thread;
        this.session = session;
    }

    public JsonNode call(String method) {
        return call(method, Map.of());
    }

    /**
     * Sends {@code method} with {@code params} (anything Jackson writes as an object) and waits for its answer, at most
     * {@link #CALL_TIMEOUT}: a browser that does not answer fails the call, it does not hang its caller.
     */
    public JsonNode call(String method, Object params) {
        JsonObject args = gson(params);
        try {
            return thread.call(() -> answer(method, args), CALL_TIMEOUT);
        } catch (BrowserThread.TimedOut e) {
            throw new CdpException(method + ": " + e.getMessage(), e);
        }
    }

    /** Sends {@code method} without waiting; the answer, or its failure, completes the future. In order with calls. */
    public CompletableFuture<JsonNode> send(String method, Object params) {
        JsonObject args = gson(params);
        return thread.submit(() -> answer(method, args));
    }

    /** Calls {@code listener} with each {@code event}'s parameters, on the {@link BrowserThread}. */
    public void on(String event, Consumer<JsonNode> listener) {
        thread.run(() -> session.on(event, params -> {
            try {
                listener.accept(jackson(params));
            } catch (RuntimeException e) {
                // A listener that failed loses its event, not the page's others.
            }
        }));
    }

    void detach() {
        thread.run(() -> {
            try {
                session.detach();
            } catch (PlaywrightException gone) {
                // Detached with its page.
            }
        });
    }

    private JsonNode answer(String method, JsonObject args) {
        try {
            return jackson(session.send(method, args));
        } catch (PlaywrightException e) {
            throw new CdpException(method + ": " + e.getMessage(), e);
        }
    }

    private static JsonObject gson(Object params) {
        try {
            return params == null ? new JsonObject()
                    : JsonParser.parseString(JSON.writeValueAsString(params)).getAsJsonObject();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("params not written", e);
        }
    }

    private static JsonNode jackson(JsonObject object) {
        try {
            return object == null ? JSON.createObjectNode() : JSON.readTree(object.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("answer not read", e);
        }
    }
}
