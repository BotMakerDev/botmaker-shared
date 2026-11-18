// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>One tab of a {@link Chromium}</b>: it opens a URL, waits for the page to settle, reads the page's tree, a frame,
 * its console and its failed requests, and presses or types into a node at its box's centre through CDP's
 * {@code Input}, which goes into the renderer, never the user's cursor.
 *
 * <p>Settled is the page doing nothing: no request in flight and no DOM mutation for a quiet while. Mutations reach
 * BotMaker through a binding a small observer calls, at most once per 50 ms; the observer draws nothing.
 */
public final class WebPage implements AutoCloseable {

    /** How long nothing must happen before a page counts as settled. */
    public static final Duration QUIET = Duration.ofMillis(500);
    /** How long a settle waits at most. */
    public static final Duration SETTLE_MAX = Duration.ofSeconds(10);

    private static final String BINDING = "__botmakerMutation";
    private static final String OBSERVER = """
            (() => {
              if (window.__botmakerObserving) return;
              window.__botmakerObserving = true;
              let pending = false;
              const report = () => { pending = false; try { %s(''); } catch (e) {} };
              new MutationObserver(() => { if (!pending) { pending = true; setTimeout(report, 50); } })
                .observe(document, {subtree: true, childList: true, attributes: true, characterData: true});
            })();
            """.formatted(BINDING);

    /** Requests that stay open by design and would keep a page from ever settling. */
    private static final Set<String> LONG_LIVED = Set.of("EventSource", "WebSocket");

    private final Cdp cdp;
    private final String targetId;
    private final Cdp.Session session;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, String> urls = new ConcurrentHashMap<>();
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private final List<ConsoleMessage> console = new ArrayList<>();
    private final List<RequestFailure> failures = new ArrayList<>();
    /** Each document's load, by the loader that brought it; emptied at each {@link #open}. */
    private final Map<String, CompletableFuture<Void>> loads = new ConcurrentHashMap<>();

    WebPage(Cdp cdp, String targetId, Cdp.Session session) {
        this.cdp = cdp;
        this.targetId = targetId;
        this.session = session;
        listen();
        session.call("Page.enable");
        session.call("Page.setLifecycleEventsEnabled", Map.of("enabled", true));
        session.call("Network.enable");
        session.call("Runtime.enable");
        session.call("Log.enable");
        session.call("DOM.enable");
        session.call("Accessibility.enable");
        session.call("Emulation.setDeviceMetricsOverride", Map.of("width", Chromium.WIDTH, "height", Chromium.HEIGHT,
                "deviceScaleFactor", 1, "mobile", false));
        session.call("Runtime.addBinding", Map.of("name", BINDING));
        session.call("Page.addScriptToEvaluateOnNewDocument", Map.of("source", OBSERVER));
    }

    /** What opening a URL gave. */
    public record Opened(String error, boolean settled) {

        public Opened {
            error = error == null ? "" : error;
        }

        /** Whether the page loaded at all. */
        public boolean ok() {
            return error.isEmpty();
        }
    }

    /**
     * Opens {@code url} in this tab, waits for the load of the document it brings (none for a move within the same
     * document, such as a {@code #part}), then for it to settle.
     */
    public Opened open(String url) {
        // The earlier documents' loads are of no use now: only the loader this navigation names is waited for.
        loads.clear();
        long start = System.nanoTime();
        JsonNode answer = session.call("Page.navigate", Map.of("url", url));
        String error = answer.path("errorText").asText("");
        if (!error.isEmpty()) {
            return new Opened(error, false);
        }
        String loader = answer.path("loaderId").asText("");
        if (!loader.isEmpty()) {
            try {
                load(loader).get(SETTLE_MAX.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Opened("", false);
            } catch (ExecutionException | TimeoutException timedOut) {
                // A page whose load event never comes is still read: the settle below decides.
            }
            loads.remove(loader);
        }
        Duration left = SETTLE_MAX.minusNanos(System.nanoTime() - start);
        return new Opened("", settled(QUIET, left.isNegative() ? Duration.ZERO : left));
    }

    /** Waits for the page to settle: {@link #QUIET} of nothing, at most {@link #SETTLE_MAX}. */
    public boolean settled() {
        return settled(QUIET, SETTLE_MAX);
    }

    /** Waits until no request is in flight and nothing changed for {@code quiet}, or {@code max} passed. */
    public boolean settled(Duration quiet, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (true) {
            long now = System.nanoTime();
            if (inFlight.isEmpty() && now - lastActivity.get() >= quiet.toNanos()) {
                return true;
            }
            if (now >= deadline) {
                return false;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Where the tab is now. */
    public String url() {
        return session.call("Runtime.evaluate", Map.of("expression", "location.href", "returnByValue", true))
                .path("result").path("value").asText("");
    }

    /**
     * The page's accessibility tree, each interactive node and dialog with its box and the attributes a locator
     * reads.
     */
    public WebTree tree() {
        session.call("DOM.getDocument", Map.of("depth", 0));
        JsonNode ax = session.call("Accessibility.getFullAXTree");
        Set<Integer> measured = new LinkedHashSet<>();
        for (JsonNode node : ax.path("nodes")) {
            Role role = Role.fromId(node.path("role").path("value").asText(null));
            int backend = node.path("backendDOMNodeId").asInt(0);
            if (backend != 0 && !node.path("ignored").asBoolean(false)
                    && (role.interactive() || role == Role.DIALOG || role == Role.ALERT_DIALOG)) {
                measured.add(backend);
            }
        }
        Map<Integer, CompletableFuture<JsonNode>> described = new HashMap<>();
        Map<Integer, CompletableFuture<JsonNode>> boxes = new HashMap<>();
        for (int backend : measured) {
            described.put(backend, session.send("DOM.describeNode", Map.of("backendNodeId", backend)));
            boxes.put(backend, session.send("DOM.getBoxModel", Map.of("backendNodeId", backend)));
        }
        Map<Integer, WebTree.Extra> extras = new HashMap<>();
        for (int backend : measured) {
            JsonNode node = orNull(described.get(backend));
            JsonNode box = orNull(boxes.get(backend));
            extras.put(backend, new WebTree.Extra(box == null ? null : rectangle(box.path("model").path("border")),
                    node == null ? Map.of() : attributes(node.path("node").path("attributes"))));
        }
        return WebTree.from(ax, extras);
    }

    /** What the viewport shows, as JPEG. */
    public byte[] frame() {
        String data = session.call("Page.captureScreenshot", Map.of("format", "jpeg", "quality", 80))
                .path("data").asText("");
        return Base64.getDecoder().decode(data);
    }

    /** The console's lines since the last call. */
    public List<ConsoleMessage> console() {
        synchronized (console) {
            List<ConsoleMessage> since = List.copyOf(console);
            console.clear();
            return since;
        }
    }

    /** The failed requests since the last call. */
    public List<RequestFailure> failures() {
        synchronized (failures) {
            List<RequestFailure> since = List.copyOf(failures);
            failures.clear();
            return since;
        }
    }

    /**
     * Presses {@code node} with the left button at its box's centre, scrolled into view first.
     *
     * @throws IllegalStateException when it has no box to press
     */
    public void click(WebNode node) {
        Point at = centre(node);
        for (String type : List.of("mouseMoved", "mousePressed", "mouseReleased")) {
            session.call("Input.dispatchMouseEvent", Map.of("type", type, "x", at.x, "y", at.y,
                    "button", type.equals("mouseMoved") ? "none" : "left", "clickCount", 1));
        }
    }

    /** Focuses {@code node} and types {@code text} into it as one insertion. */
    public void type(WebNode node, String text) {
        session.call("DOM.focus", Map.of("backendNodeId", node.backendId()));
        session.call("Input.insertText", Map.of("text", text));
    }

    /** The tab's own commands and events, for what this class does not say yet. */
    public Cdp.Session session() {
        return session;
    }

    @Override
    public void close() {
        try {
            cdp.browser().call("Target.closeTarget", Map.of("targetId", targetId));
        } catch (RuntimeException ignored) {
            // A closed browser closed its tabs.
        }
        cdp.forget(session.id());
    }

    private CompletableFuture<Void> load(String loader) {
        return loads.computeIfAbsent(loader, k -> new CompletableFuture<>());
    }

    private Point centre(WebNode node) {
        if (node.backendId() == 0) {
            throw new IllegalStateException("no DOM node to press: " + node.line());
        }
        session.call("DOM.scrollIntoViewIfNeeded", Map.of("backendNodeId", node.backendId()));
        Optional<Rectangle> box = Optional.ofNullable(orNull(session.send("DOM.getBoxModel",
                Map.of("backendNodeId", node.backendId())))).map(b -> rectangle(b.path("model").path("border")));
        Rectangle r = box.filter(b -> b.width > 0 && b.height > 0)
                .orElseThrow(() -> new IllegalStateException("not laid out: " + node.line()));
        return new Point(r.x + r.width / 2, r.y + r.height / 2);
    }

    private void listen() {
        session.on("Page.loadEventFired", params -> touch());
        session.on("Page.lifecycleEvent", params -> {
            // Only the main frame's: a frame's own frameId is its target's id.
            if ("load".equals(params.path("name").asText()) && targetId.equals(params.path("frameId").asText())) {
                load(params.path("loaderId").asText("")).complete(null);
            }
        });
        session.on("Runtime.bindingCalled", params -> {
            if (BINDING.equals(params.path("name").asText())) {
                touch();
            }
        });
        session.on("Network.requestWillBeSent", params -> {
            String id = params.path("requestId").asText();
            urls.put(id, params.path("request").path("url").asText(""));
            if (!LONG_LIVED.contains(params.path("type").asText())) {
                inFlight.add(id);
            }
            touch();
        });
        session.on("Network.responseReceived", params -> {
            int status = params.path("response").path("status").asInt(0);
            if (status >= 400) {
                addFailure(new RequestFailure(params.path("response").path("url").asText(""), status, ""));
            }
            touch();
        });
        session.on("Network.loadingFinished", params -> {
            done(params.path("requestId").asText());
            urls.remove(params.path("requestId").asText());
        });
        session.on("Network.loadingFailed", params -> {
            String id = params.path("requestId").asText();
            if (!params.path("canceled").asBoolean(false)) {
                addFailure(new RequestFailure(urls.getOrDefault(id, ""), 0, params.path("errorText").asText("")));
            }
            done(id);
            urls.remove(id);
        });
        session.on("Runtime.consoleAPICalled", params -> {
            List<String> parts = new ArrayList<>();
            for (JsonNode arg : params.path("args")) {
                parts.add(arg.has("value") ? arg.path("value").asText() : arg.path("description").asText(""));
            }
            JsonNode frames = params.path("stackTrace").path("callFrames");
            addConsole(new ConsoleMessage(ConsoleMessage.Level.fromId(params.path("type").asText()),
                    String.join(" ", parts), frames.isEmpty() ? "" : frames.get(0).path("url").asText("")));
        });
        session.on("Runtime.exceptionThrown", params -> {
            JsonNode details = params.path("exceptionDetails");
            String text = details.path("exception").path("description").asText(details.path("text").asText(""));
            addConsole(new ConsoleMessage(ConsoleMessage.Level.ERROR, text, details.path("url").asText("")));
        });
        session.on("Log.entryAdded", params -> {
            JsonNode entry = params.path("entry");
            addConsole(new ConsoleMessage(ConsoleMessage.Level.fromId(entry.path("level").asText()),
                    entry.path("text").asText(""), entry.path("url").asText("")));
        });
    }

    private void done(String requestId) {
        inFlight.remove(requestId);
        touch();
    }

    private void touch() {
        lastActivity.set(System.nanoTime());
    }

    private void addConsole(ConsoleMessage message) {
        synchronized (console) {
            console.add(message);
        }
    }

    private void addFailure(RequestFailure failure) {
        synchronized (failures) {
            failures.add(failure);
        }
    }

    private static JsonNode orNull(CompletableFuture<JsonNode> answer) {
        try {
            return answer.get(Cdp.CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception notLaidOutOrGone) {
            return null;
        }
    }

    /** A quad's bounding rectangle: {@code [x1, y1, … x4, y4]}. */
    static Rectangle rectangle(JsonNode quad) {
        if (!quad.isArray() || quad.size() < 8) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i += 2) {
            minX = Math.min(minX, quad.get(i).asDouble());
            maxX = Math.max(maxX, quad.get(i).asDouble());
            minY = Math.min(minY, quad.get(i + 1).asDouble());
            maxY = Math.max(maxY, quad.get(i + 1).asDouble());
        }
        return new Rectangle((int) Math.round(minX), (int) Math.round(minY),
                (int) Math.round(maxX - minX), (int) Math.round(maxY - minY));
    }

    /** {@code DOM.Node.attributes}: names and values, interleaved. */
    static Map<String, String> attributes(JsonNode flat) {
        Map<String, String> attributes = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            attributes.put(flat.get(i).asText(), flat.get(i + 1).asText());
        }
        return attributes;
    }
}
