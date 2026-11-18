// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * <b>The read-only guard of one tab</b> (spec §3): every request the tab makes pauses in CDP's {@code Fetch} and is
 * judged before it leaves.
 * <ul>
 *   <li>A navigation of the tab to a page off the {@link Scope} is failed: {@link Kind#EXIT}, its URL recorded,
 *       nothing sent to the other host. Each redirect is judged again, so where a navigation lands decides.</li>
 *   <li>A navigation {@code robots.txt} disallows is failed: {@link Kind#ROBOTS}.</li>
 *   <li>Once a press was made, a {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} to the site is failed:
 *       {@link Kind#WRITE}.</li>
 *   <li>Everything else passes, the page's own requests to other hosts (scripts, fonts, analytics) included: the
 *       visitor's, not ours.</li>
 * </ul>
 * A failed navigation is aborted, so the tab stays on the page it was on. A page the tab opens (a popup, a
 * {@code target=_blank} tab) is attached paused by {@link Chromium} and guarded as the tab before it runs.
 * {@link #press} asks {@link Presses} first.
 *
 * <p>A request is judged on the socket's thread, but a navigation on a thread of the guard's own, since judging one
 * may fetch a {@code robots.txt}.
 */
public final class Guard implements AutoCloseable {

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** What the guard stopped. */
    public enum Kind {
        EXIT("exit", "Leaves the site"),
        WRITE("write", "Blocked write"),
        ROBOTS("robots", "Not visited (robots.txt)"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The kind of {@code id}; {@link #UNKNOWN} for one this version does not know. */
        public static Kind fromId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equals(id)) {
                    return kind;
                }
            }
            return UNKNOWN;
        }
    }

    /** One request stopped: why, where to and how. */
    public record Stopped(Kind kind, String url, String method) {

        public Stopped {
            url = url == null ? "" : url;
            method = method == null ? "" : method;
        }
    }

    private final WebPage page;
    private final Scope scope;
    private final Robots robots;
    private final String userAgent;
    private final ExecutorService navigations = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon()
            .name("botmaker-web-guard").factory());
    private final List<Stopped> stopped = new ArrayList<>();
    private final List<Cdp.Session> guarded = new ArrayList<>();
    private volatile boolean pressed;
    private volatile boolean closed;

    private Guard(WebPage page, Scope scope, Robots robots, String userAgent) {
        this.page = page;
        this.scope = scope;
        this.robots = robots;
        this.userAgent = userAgent;
    }

    /**
     * Guards {@code page} from now on, its requests carrying {@code robots}' user agent after the browser's own.
     * Before its first {@link WebPage#open}.
     */
    public static Guard on(WebPage page, Scope scope, Robots robots) {
        Cdp.Session session = page.session();
        String browser = session.call("Browser.getVersion").path("userAgent").asText("");
        Guard guard = new Guard(page, scope, robots, (browser.isEmpty() ? "" : browser + " ") + robots.userAgent());
        session.call("Network.setUserAgentOverride", Map.of("userAgent", guard.userAgent));
        guard.listen(session, page.targetId());
        session.call("Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request"))));
        page.onOpened(page.targetId(), guard::attached);
        return guard;
    }

    public Scope scope() {
        return scope;
    }

    public Robots robots() {
        return robots;
    }

    /**
     * Presses {@code node} unless {@link Presses} refuses it; from then on, writes to the site are failed.
     *
     * @return the refusal, empty when pressed
     */
    public Optional<Presses.Refusal> press(WebNode node) {
        Optional<Presses.Refusal> refused = Presses.press(node, page.pressFacts(node));
        if (refused.isPresent()) {
            return refused;
        }
        arm();
        page.click(node);
        return Optional.empty();
    }

    /** Types {@code text} into {@code node}; from then on, writes to the site are failed. Typing submits nothing. */
    public void type(WebNode node, String text) {
        arm();
        page.type(node, text);
    }

    /** From now on, writes to the site are failed: what a press does, with nothing pressed. */
    void arm() {
        pressed = true;
    }

    /** What was stopped since the last call. */
    public List<Stopped> stopped() {
        synchronized (stopped) {
            List<Stopped> since = List.copyOf(stopped);
            stopped.clear();
            return since;
        }
    }

    /** Stops guarding: what is paused is judged first, then {@code Fetch} lets everything through again. */
    @Override
    public void close() {
        closed = true;
        navigations.shutdown();
        try {
            navigations.awaitTermination(Cdp.CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Cdp.Session> sessions;
        synchronized (guarded) {
            sessions = List.copyOf(guarded);
        }
        for (Cdp.Session session : sessions) {
            session.send("Fetch.disable", Map.of());
        }
    }

    /** Judges the requests of {@code session}, a tab whose main frame is {@code targetId}. */
    private void listen(Cdp.Session session, String targetId) {
        synchronized (guarded) {
            guarded.add(session);
        }
        session.on("Fetch.requestPaused", params -> {
            boolean navigation = "Document".equals(params.path("resourceType").asText())
                    && targetId.equals(params.path("frameId").asText());
            if (!navigation) {
                answer(session, params, false);
                return;
            }
            try {
                navigations.execute(() -> answer(session, params, true));
            } catch (RejectedExecutionException closing) {
                // Closing: no robots.txt fetch on the socket's thread; a navigation then is simply not made.
                session.send("Fetch.failRequest", Map.of("requestId", params.path("requestId").asText(),
                        "errorReason", "Aborted"));
            }
        });
    }

    /**
     * A page the tab (or a page it opened) opened, paused before it runs: guarded as the tab, then let go. Sends only,
     * on the socket's thread: they leave in order, so {@code Fetch} is on before the page runs.
     */
    private void attached(JsonNode params) {
        Cdp.Session opened = page.session(params.path("sessionId").asText());
        if (closed) {
            opened.send("Runtime.runIfWaitingForDebugger", Map.of());
            return;
        }
        String target = params.path("targetInfo").path("targetId").asText();
        listen(opened, target);
        page.onOpened(target, this::attached);
        opened.send("Network.setUserAgentOverride", Map.of("userAgent", userAgent));
        opened.send("Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request"))));
        opened.send("Runtime.runIfWaitingForDebugger", Map.of());
    }

    private void answer(Cdp.Session session, JsonNode params, boolean navigation) {
        String id = params.path("requestId").asText();
        JsonNode request = params.path("request");
        String url = request.path("url").asText("");
        String method = request.path("method").asText("GET");
        Optional<Kind> verdict;
        try {
            verdict = verdict(url, method, navigation);
        } catch (RuntimeException e) {
            // A judgement that could not be made lets nothing through.
            verdict = Optional.of(navigation ? Kind.EXIT : Kind.WRITE);
        }
        if (verdict.isEmpty()) {
            session.send("Fetch.continueRequest", Map.of("requestId", id));
            return;
        }
        synchronized (stopped) {
            stopped.add(new Stopped(verdict.get(), url, method));
        }
        // Aborted, not blocked: an aborted navigation commits no error page, so the tab stays where it was.
        session.send("Fetch.failRequest", Map.of("requestId", id,
                "errorReason", navigation ? "Aborted" : "BlockedByClient"));
    }

    private Optional<Kind> verdict(String url, String method, boolean navigation) {
        boolean onSite = scope.contains(url);
        if (onSite && pressed && WRITES.contains(method.toUpperCase(Locale.ROOT))) {
            return Optional.of(Kind.WRITE);
        }
        if (!navigation) {
            return Optional.empty();
        }
        if (!onSite) {
            return Optional.of(Kind.EXIT);
        }
        return robots.allows(URI.create(url)) ? Optional.empty() : Optional.of(Kind.ROBOTS);
    }
}
