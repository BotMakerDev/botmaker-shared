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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    /**
     * Reports the page's changes, but not an animation's: a change of a {@code style} attribute counts only when it
     * shows or hides the node, its inline {@code display} or {@code visibility} (the rest is what a script animates,
     * frame by frame or box after box), and a node that changed {@value #ANIMATING_STREAK} times in a row, each within
     * {@value #ANIMATING_GAP_MS} ms of the one before, is animating: its changes stop counting until it rests that
     * long. A press's answer adds, removes or retexts nodes that were at rest, so it counts.
     */
    private static final String OBSERVER = """
            (() => {
              if (window.__botmakerObserving) return;
              window.__botmakerObserving = true;
              let pending = false;
              const seen = new WeakMap();
              const animating = node => {
                const now = performance.now();
                const was = seen.get(node) || {last: -1e9, streak: 0};
                const streak = now - was.last < %d ? was.streak + 1 : 0;
                seen.set(node, {last: now, streak});
                return streak >= %d;
              };
              const probe = document.createElement('div');
              const shown = css => {
                probe.style.cssText = css || '';
                return probe.style.display + '|' + probe.style.visibility;
              };
              const report = () => { pending = false; try { %s(''); } catch (e) {} };
              new MutationObserver(records => {
                let counts = false;
                for (const r of records) {
                  // An element added or removed always counts: it is never a frame of an animation, and a
                  // parent a ticker keeps appending to must not hide what a press puts beside it.
                  const element = n => n.nodeType === 1;
                  if (r.type === 'childList' && ([...r.addedNodes].some(element)
                      || [...r.removedNodes].some(element))) { counts = true; continue; }
                  // A style change is what a script animates, frame by frame or box after box in a reveal; only
                  // one that shows or hides the node counts, as a panel opened by display: block.
                  if (r.type === 'attributes' && r.attributeName === 'style') {
                    if (shown(r.oldValue) !== shown(r.target.getAttribute('style'))) counts = true;
                    continue;
                  }
                  if (!animating(r.target)) counts = true;
                }
                if (counts && !pending) { pending = true; setTimeout(report, 50); }
              }).observe(document, {subtree: true, childList: true, attributes: true, attributeOldValue: true,
                  characterData: true});
            })();
            """.formatted(WebPage.ANIMATING_GAP_MS, WebPage.ANIMATING_STREAK, BINDING);
    private static final int ANIMATING_GAP_MS = 300;
    private static final int ANIMATING_STREAK = 4;

    /** The element's press facts: the control the node is or sits in, and its form. */
    private static final String PRESS_FACTS = """
            function() {
              const el = this.nodeType === 1 ? this : this.parentElement;
              if (!el) return {submit: false, inForm: false, formAction: ''};
              const control = el.closest('button, input') || el;
              const form = control.form || el.closest('form');
              const tag = control.tagName;
              const type = (control.getAttribute('type') || (tag === 'BUTTON' ? 'submit' : '')).toLowerCase();
              const submit = !!control.form && ((tag === 'BUTTON' && type === 'submit')
                  || (tag === 'INPUT' && (type === 'submit' || type === 'image')));
              return {submit, inForm: !!form, formAction: form ? (form.getAttribute('action') || '') : ''};
            }
            """;

    /**
     * Requests that stay open by design and would keep a page from ever settling: a video or sound streams as it plays
     * (nunu.ai's landing video, 2026-11-20).
     */
    private static final Set<String> LONG_LIVED = Set.of("EventSource", "WebSocket", "Media");

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

    /** The browser's: who guards the pages a tab opens, by the opener's target id. */
    private final Map<String, Consumer<JsonNode>> openers;
    /** The openers this tab put in {@link #openers}: itself and the pages it opened. */
    private final Set<String> opening = ConcurrentHashMap.newKeySet();

    WebPage(Cdp cdp, String targetId, Cdp.Session session, Map<String, Consumer<JsonNode>> openers) {
        this.cdp = cdp;
        this.targetId = targetId;
        this.session = session;
        this.openers = openers;
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

    /**
     * The page's part inside {@code box}, as PNG, rendered by Chromium even where the viewport does not reach: a
     * tree's box ({@link #tree}) is the viewport's, so the page's scroll is added to clip it from the whole page.
     */
    public byte[] picture(Rectangle box) {
        JsonNode view = session.call("Page.getLayoutMetrics").path("cssVisualViewport");
        Map<String, Object> clip = Map.of("x", box.x + view.path("pageX").asDouble(0),
                "y", box.y + view.path("pageY").asDouble(0), "width", box.width, "height", box.height, "scale", 1);
        String data = session.call("Page.captureScreenshot", Map.of("format", "png", "captureBeyondViewport", true,
                "clip", clip)).path("data").asText("");
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
        clickAt(centre(node));
    }

    /** Focuses {@code node} and types {@code text} into it as one insertion. */
    public void type(WebNode node, String text) {
        session.call("DOM.focus", Map.of("backendNodeId", node.backendId()));
        insert(text);
    }

    /**
     * Presses the left button at {@code at}, in viewport CSS pixels: a press no node of the tree names.
     *
     * <p>Every input counts as activity, so a settle after it waits at least {@link #QUIET}: an answer the page shows
     * a moment later, or a change whose report is still on its way, is not missed.
     */
    public void clickAt(Point at) {
        for (String type : List.of("mouseMoved", "mousePressed", "mouseReleased")) {
            session.call("Input.dispatchMouseEvent", Map.of("type", type, "x", at.x, "y", at.y,
                    "button", type.equals("mouseMoved") ? "none" : "left", "clickCount", 1));
        }
        touch();
    }

    /** Turns the wheel at {@code at} by {@code dx}, {@code dy} CSS pixels: positive scrolls right and down. */
    public void wheel(Point at, int dx, int dy) {
        session.call("Input.dispatchMouseEvent", Map.of("type", "mouseWheel", "x", at.x, "y", at.y,
                "deltaX", dx, "deltaY", dy));
        touch();
    }

    /** Types {@code text} into whatever has the focus, as one insertion. */
    public void insert(String text) {
        session.call("Input.insertText", Map.of("text", text));
        touch();
    }

    /**
     * Presses and releases the key {@code key}, a DOM key name ({@code Enter}, {@code Escape}, {@code Tab},
     * {@code ArrowDown}, {@code a}).
     */
    public void key(String key) {
        char c = key.length() == 1 ? key.charAt(0) : 0;
        String code = Character.isLetter(c) ? "Key" + key.toUpperCase(Locale.ROOT)
                : Character.isDigit(c) ? "Digit" + key : c == ' ' ? "Space" : key;
        // A key that types carries its text on keyDown, as Chrome's own input does: without it a letter types
        // nothing and Enter neither submits nor follows a focused link. Any other key goes down raw.
        String text = key.equals("Enter") ? "\r" : key.length() == 1 ? key : null;
        Map<String, Object> down = new HashMap<>(Map.of("type", text == null ? "rawKeyDown" : "keyDown", "key", key,
                "code", code, "windowsVirtualKeyCode", virtualKey(key)));
        if (text != null) {
            down.put("text", text);
            down.put("unmodifiedText", text);
        }
        session.call("Input.dispatchKeyEvent", down);
        session.call("Input.dispatchKeyEvent", Map.of("type", "keyUp", "key", key, "code", code,
                "windowsVirtualKeyCode", virtualKey(key)));
        touch();
    }

    /** The Windows virtual key code CDP needs for a key's default action (Enter submitting, Tab moving focus). */
    private static int virtualKey(String key) {
        return switch (key) {
            case "Enter" -> 13;
            case "Escape" -> 27;
            case "Tab" -> 9;
            case "Backspace" -> 8;
            case " " -> 32;
            case "ArrowLeft" -> 37;
            case "ArrowUp" -> 38;
            case "ArrowRight" -> 39;
            case "ArrowDown" -> 40;
            case "PageUp" -> 33;
            case "PageDown" -> 34;
            case "Home" -> 36;
            case "End" -> 35;
            case "Delete" -> 46;
            default -> key.length() == 1 ? Character.toUpperCase(key.charAt(0))
                    : key.matches("F([1-9]|1[0-2])") ? 111 + Integer.parseInt(key.substring(1)) : 0;
        };
    }

    /** Closes the page {@code targetId}, one this tab opened; a page already gone is no error. */
    void closeOpened(String targetId) {
        try {
            cdp.browser().send("Target.closeTarget", Map.of("targetId", targetId));
        } catch (RuntimeException gone) {
            // Closed by itself, or the browser went.
        }
    }

    /**
     * What the page says of {@code node}'s element that its tree does not: whether a press would submit a form. Empty
     * when the element cannot be read (no DOM node, detached, the call failed).
     */
    public Optional<Presses.Facts> pressFacts(WebNode node) {
        if (node.backendId() == 0) {
            return Optional.empty();
        }
        try {
            String object = session.call("DOM.resolveNode", Map.of("backendNodeId", node.backendId()))
                    .path("object").path("objectId").asText("");
            if (object.isEmpty()) {
                return Optional.empty();
            }
            JsonNode answer = session.call("Runtime.callFunctionOn", Map.of("objectId", object,
                    "returnByValue", true, "functionDeclaration", PRESS_FACTS));
            session.send("Runtime.releaseObject", Map.of("objectId", object));
            JsonNode facts = answer.path("result").path("value");
            if (answer.has("exceptionDetails") || !facts.isObject()) {
                return Optional.empty();
            }
            return Optional.of(new Presses.Facts(facts.path("submit").asBoolean(false),
                    facts.path("inForm").asBoolean(false), facts.path("formAction").asText("")));
        } catch (Cdp.CdpException goneOrDetached) {
            return Optional.empty();
        }
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
        opening.forEach(openers::remove);
    }

    /** The session of a target this tab opened, which the browser attached by itself. */
    Cdp.Session session(String sessionId) {
        return cdp.session(sessionId);
    }

    /**
     * Gives each page {@code opener} opens (this tab, or a page it opened) to {@code guard}, attached and paused before
     * it runs: {@code Target.attachedToTarget}'s parameters. Until this tab closes.
     */
    void onOpened(String opener, Consumer<JsonNode> guard) {
        opening.add(opener);
        openers.put(opener, guard);
    }

    /** The tab's target, which is its main frame's id too. */
    String targetId() {
        return targetId;
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
            // Only a request waited for is kept: one never finishing here would stay in urls for the tab's life.
            if (!LONG_LIVED.contains(params.path("type").asText()) && !workerScript(params)) {
                urls.put(id, params.path("request").path("url").asText(""));
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

    /**
     * A worker's own script: a script no parser or script of the page asked for, its initiator {@code other}. The
     * worker loads it in its own target, so this tab never hears that it finished; waiting for it held every settle
     * of nunu.ai, whose worker comes from a {@code blob:} URL, to {@link #SETTLE_MAX} (2026-11-20).
     */
    private static boolean workerScript(JsonNode request) {
        return "Script".equals(request.path("type").asText())
                && "other".equals(request.path("initiator").path("type").asText());
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
