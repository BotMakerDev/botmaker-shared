// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * <b>A client of the Chrome DevTools Protocol</b> over the JDK's {@link WebSocket} and Jackson: no Playwright or
 * Selenium. A command is a message with an id, answered by the message carrying the same id; anything else is an
 * event, given to the listeners of its method. Targets (tabs) are reached in flat mode: one socket, each command and
 * event of a tab carrying its {@link Session}'s id.
 *
 * <p>Listeners run on the socket's thread: they record and return, never wait on another command.
 */
public final class Cdp implements AutoCloseable {

    /** How long {@link Session#call} waits for an answer. */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What carries the messages: the browser's socket, or a test's list. */
    interface Wire {
        /** Sends one message; the stage fails when the message could not be sent. */
        CompletionStage<?> send(String text);

        void close();
    }

    private final Wire wire;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<JsonNode>>> listeners = new ConcurrentHashMap<>();
    private final Session browser;
    private volatile String closedBecause;

    Cdp(Wire wire) {
        this.wire = wire;
        this.browser = new Session(this, "");
    }

    /** Connects to a browser's {@code webSocketDebuggerUrl}. */
    public static Cdp connect(URI uri) throws IOException {
        SocketWire wire = new SocketWire(HttpClient.newHttpClient());
        Cdp cdp = new Cdp(wire);
        wire.cdp = cdp;
        try {
            wire.socket = wire.client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, wire)
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            wire.close();
            throw new IOException("interrupted connecting to " + uri, e);
        } catch (ExecutionException | TimeoutException e) {
            wire.close();
            throw new IOException("cannot connect to " + uri + ": " + e.getMessage(), e);
        }
        return cdp;
    }

    /** The browser's own session, for {@code Target} and {@code Browser} commands. */
    public Session browser() {
        return browser;
    }

    /** Attaches to a target in flat mode and returns its session. */
    public Session attach(String targetId) {
        JsonNode answer = browser.call("Target.attachToTarget", Map.of("targetId", targetId, "flatten", true));
        return new Session(this, answer.path("sessionId").asText());
    }

    /** The session of a target the browser attached by itself ({@code Target.attachedToTarget}). */
    Session session(String sessionId) {
        return new Session(this, sessionId);
    }

    /** A new empty map of parameters, for a command whose parameters are nested. */
    public static ObjectNode params() {
        return JSON.createObjectNode();
    }

    /** A command failed: Chromium's answer was an error, or the connection closed first. */
    public static final class CdpException extends RuntimeException {
        private final int code;

        CdpException(String method, int code, String message) {
            super(method + ": " + message + (code == 0 ? "" : " (" + code + ")"));
            this.code = code;
        }

        /** Chromium's error code, {@code 0} when the connection closed. */
        public int code() {
            return code;
        }
    }

    /** A browser's or a tab's commands and events. */
    public static final class Session {
        private final Cdp cdp;
        private final String id;

        private Session(Cdp cdp, String id) {
            this.cdp = cdp;
            this.id = id;
        }

        /** The session's id, {@code ""} for the browser's own. */
        public String id() {
            return id;
        }

        public CompletableFuture<JsonNode> send(String method, Object params) {
            return cdp.send(id, method, params);
        }

        /** Sends and waits {@link #CALL_TIMEOUT} for the result. */
        public JsonNode call(String method, Object params) {
            try {
                return send(method, params).get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CdpException(method, 0, "interrupted");
            } catch (TimeoutException e) {
                throw new CdpException(method, 0, "no answer in " + CALL_TIMEOUT.toSeconds() + " s");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof CdpException failed ? failed
                        : new CdpException(method, 0, String.valueOf(e.getCause()));
            }
        }

        public JsonNode call(String method) {
            return call(method, Map.of());
        }

        /** Listens to this session's events of {@code method}; the listener gets their {@code params}. */
        public void on(String method, Consumer<JsonNode> listener) {
            cdp.listeners.computeIfAbsent(id + '\u0000' + method, k -> new CopyOnWriteArrayList<>()).add(listener);
        }
    }

    private CompletableFuture<JsonNode> send(String sessionId, String method, Object params) {
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        if (closedBecause != null) {
            answer.completeExceptionally(new CdpException(method, 0, "closed: " + closedBecause));
            return answer;
        }
        int id = ids.incrementAndGet();
        ObjectNode message = JSON.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : JSON.valueToTree(params));
        if (!sessionId.isEmpty()) {
            message.put("sessionId", sessionId);
        }
        pending.put(id, new Pending(method, answer));
        if (closedBecause != null) {
            // closed() ran between the check above and the put, and so missed this command.
            fail(id, "closed: " + closedBecause);
            return answer;
        }
        try {
            wire.send(message.toString()).whenComplete((sent, error) -> {
                if (error != null) {
                    fail(id, "cannot send: " + error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            fail(id, "cannot send: " + e.getMessage());
        }
        return answer;
    }

    private void fail(int id, String why) {
        Pending waiting = pending.remove(id);
        if (waiting != null) {
            waiting.answer.completeExceptionally(new CdpException(waiting.method, 0, why));
        }
    }

    /** Drops every listener of one session, once its tab is closed. */
    void forget(String sessionId) {
        listeners.keySet().removeIf(key -> key.startsWith(sessionId + '\u0000'));
    }

    /** One whole message from the browser. */
    void receive(String text) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (IOException e) {
            return;
        }
        if (message.has("id")) {
            Pending waiting = pending.remove(message.path("id").asInt());
            if (waiting == null) {
                return;
            }
            JsonNode error = message.path("error");
            if (error.isMissingNode()) {
                waiting.answer.complete(message.path("result"));
            } else {
                waiting.answer.completeExceptionally(new CdpException(waiting.method, error.path("code").asInt(),
                        error.path("message").asText()));
            }
            return;
        }
        String key = message.path("sessionId").asText("") + '\u0000' + message.path("method").asText();
        List<Consumer<JsonNode>> wanted = listeners.get(key);
        if (wanted != null) {
            JsonNode params = message.path("params");
            for (Consumer<JsonNode> listener : wanted) {
                try {
                    listener.accept(params);
                } catch (RuntimeException ignored) {
                    // One listener's fault ends no other's, nor the connection.
                }
            }
        }
    }

    /** The connection ended: every command still waiting fails. */
    void closed(String because) {
        closedBecause = because;
        for (Integer id : List.copyOf(pending.keySet())) {
            fail(id, "closed: " + because);
        }
    }

    @Override
    public void close() {
        closed("closed by BotMaker");
        wire.close();
    }

    private record Pending(String method, CompletableFuture<JsonNode> answer) {}

    /** The socket: a message may arrive in parts, and only one send may be in flight. */
    private static final class SocketWire implements Wire, WebSocket.Listener {
        private final StringBuilder part = new StringBuilder();
        private final HttpClient client;
        private volatile Cdp cdp;
        private volatile WebSocket socket;
        private CompletableFuture<WebSocket> last = CompletableFuture.completedFuture(null);

        SocketWire(HttpClient client) {
            this.client = client;
        }

        @Override
        public synchronized CompletionStage<?> send(String text) {
            WebSocket open = socket;
            if (open == null) {
                throw new IllegalStateException("not connected");
            }
            // One send in flight at a time; a failed one fails its own command, not the next.
            CompletableFuture<WebSocket> sent = last.handle((ws, e) -> null)
                    .thenCompose(ignored -> open.sendText(text, true));
            last = sent;
            return sent;
        }

        @Override
        public void close() {
            WebSocket open = socket;
            if (open != null) {
                open.abort();
            }
            client.close();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean whole) {
            part.append(data);
            if (whole) {
                String message = part.toString();
                part.setLength(0);
                cdp.receive(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            cdp.closed("socket closed " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            cdp.closed("socket failed: " + error);
        }
    }
}
