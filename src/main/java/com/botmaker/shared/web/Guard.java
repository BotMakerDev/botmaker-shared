// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;

import java.awt.Point;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>The read-only guard of one tab</b> (spec §3): every request the tab, or a page it opened, makes is judged before
 * it leaves.
 * <ul>
 *   <li>A navigation of the tab to a page off the {@link Scope} is failed: {@link Kind#EXIT}, its URL recorded,
 *       nothing sent to the other host. Each redirect is judged again, so where a navigation lands decides.</li>
 *   <li>A navigation {@code robots.txt} disallows is failed: {@link Kind#ROBOTS}.</li>
 *   <li>Once a press was made, a {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} to the site is failed:
 *       {@link Kind#WRITE}.</li>
 *   <li>Everything else passes, the page's own requests to other hosts (scripts, fonts, analytics) included: the
 *       visitor's, not ours.</li>
 * </ul>
 * A failed navigation is aborted, so the tab stays on the page it was on. {@link #press} asks {@link Presses} first.
 *
 * <p>Two hands, both on the {@link BrowserThread}: the tab's own documents pause in CDP's {@code Fetch} on its session,
 * which sees each redirect as a request of its own (a Playwright route sees only a chain's first); every other request
 * of the tab's context, a popup's or a {@code target=_blank} tab's included, goes through a Playwright route, which
 * holds a page the tab opens before its first request. Such a page's navigations never run in it ({@link #routed}): one
 * on the site is loaded in the tab instead. Each request is judged on that thread, inside the handler: a paused request
 * is answered only there, since the call waiting for it (a navigation) holds the thread. A host's first
 * {@code robots.txt} is fetched there too, so the browser is not heard until it answers; {@link Robots} keeps it after.
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

    /** The route that judges every request of the context. */
    private static final String EVERY = "**/*";
    /** What the tab's own {@code Fetch} pauses: its documents, the main frame's and its frames'. */
    private static final Map<String, Object> DOCUMENTS = Map.of("patterns", List.of(Map.of("urlPattern", "*",
            "resourceType", "Document", "requestStage", "Request")));

    private final WebPage page;
    private final Scope scope;
    private final Robots robots;
    private final String userAgent;
    private final List<Stopped> stopped = new ArrayList<>();
    private volatile boolean pressed;
    private volatile boolean closed;
    /** The cookies before the last input; null before one, or when they could not be read. */
    private volatile List<Cookie> jar;

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
        PageCdp session = page.session();
        String browser = session.call("Browser.getVersion").path("userAgent").asText("");
        Guard guard = new Guard(page, scope, robots, (browser.isEmpty() ? "" : browser + " ") + robots.userAgent());
        session.call("Network.setUserAgentOverride", Map.of("userAgent", guard.userAgent));
        session.on("Fetch.requestPaused", guard::paused);
        session.call("Fetch.enable", DOCUMENTS);
        page.thread().run(() -> {
            BrowserContext context = page.page().context();
            // What the tab opens sends the agent too, and is routed before its first request.
            context.setExtraHTTPHeaders(Map.of("User-Agent", guard.userAgent));
            context.route(EVERY, guard::routed);
        });
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

    /** Presses at {@code at}, a point no node of the tree names; from then on, writes to the site are failed. */
    public void pressAt(Point at) {
        arm();
        page.clickAt(at);
    }

    /** Presses the key {@code key} ({@link WebPage#key}); from then on, writes to the site are failed. */
    public void key(String key) {
        arm();
        page.key(key);
    }

    /** Types {@code text} where the focus is; from then on, writes to the site are failed. */
    public void insert(String text) {
        arm();
        page.insert(text);
    }

    /**
     * From now on, writes to the site are failed: what a press does, with nothing pressed. The cookies are kept as
     * they are now, for a navigation this input starts that is turned back.
     */
    void arm() {
        pressed = true;
        try {
            jar = page.cookies();
        } catch (RuntimeException e) {
            jar = null;
        }
    }

    /** What was stopped since the last call. */
    public List<Stopped> stopped() {
        synchronized (stopped) {
            List<Stopped> since = List.copyOf(stopped);
            stopped.clear();
            return since;
        }
    }

    /** Stops guarding: from now on every request is let through, the tab's {@code Fetch} and the route taken off. */
    @Override
    public void close() {
        closed = true;
        try {
            page.session().call("Fetch.disable");
            page.thread().run(() -> page.page().context().unroute(EVERY));
        } catch (RuntimeException gone) {
            // A closed tab takes both with it.
        }
    }

    /** A document of the tab, paused in its {@code Fetch}: a navigation when it is the main frame's. */
    private void paused(JsonNode params) {
        String id = params.path("requestId").asText();
        JsonNode request = params.path("request");
        String url = request.path("url").asText("");
        String method = request.path("method").asText("GET");
        boolean navigation = page.targetId().equals(params.path("frameId").asText());
        Optional<Kind> verdict = closed ? Optional.empty() : judged(url, method, navigation);
        if (verdict.isEmpty()) {
            page.session().send("Fetch.continueRequest", Map.of("requestId", id));
            return;
        }
        stop(verdict.get(), url, method, navigation);
        page.session().send("Fetch.failRequest", Map.of("requestId", id,
                "errorReason", navigation ? "Aborted" : "BlockedByClient"));
    }

    /**
     * A request of the tab's context, routed: the tab's own documents were judged in its {@code Fetch} and pass. A
     * navigation of a page the tab opened never runs there, since Playwright tells of such a page only once its first
     * navigation followed its redirects, which nothing could judge: it is aborted and the page closed; off the site it
     * is an exit, on the site it is loaded in the tab instead, whose {@code Fetch} judges each redirect.
     */
    private void routed(Route route) {
        try {
            Request request = route.request();
            boolean navigation = request.isNavigationRequest();
            Page opened = null;
            try {
                Frame frame = request.frame();
                navigation &= frame.parentFrame() == null;
                opened = frame.page();
            } catch (PlaywrightException noFrameYet) {
                // A popup's first navigation, made before its frame exists; or a worker's request, never a navigation.
            }
            boolean tab = opened == page.page();
            if (closed || tab && "Document".equalsIgnoreCase(request.resourceType())) {
                route.resume();
                return;
            }
            Optional<Kind> verdict = judged(request.url(), request.method(), navigation);
            if (navigation && !tab) {
                route.abort("aborted");
                closeOpened(opened);
                if (verdict.isEmpty()) {
                    inTheTab(request.url());
                } else {
                    stop(verdict.get(), request.url(), request.method(), true);
                }
                return;
            }
            if (verdict.isEmpty()) {
                route.resume();
                return;
            }
            stop(verdict.get(), request.url(), request.method(), navigation);
            route.abort(navigation ? "aborted" : "blockedbyclient");
        } catch (PlaywrightException gone) {
            // Its page closed while it was routed: nothing is left to let through or turn back.
        }
    }

    /**
     * Loads {@code url} in the tab, from a thread of its own: the call that pressed is still on the browser thread,
     * and the load waits for it.
     */
    private void inTheTab(String url) {
        Thread.ofVirtual().name("botmaker-guard").start(() -> {
            try {
                page.session().call("Page.navigate", Map.of("url", url));
            } catch (RuntimeException gone) {
                // The tab closed meanwhile.
            }
        });
    }

    /** Closes {@code opened}, or, for a popup turned back before its frame existed, each blank page the tab opened. */
    private void closeOpened(Page opened) {
        page.thread().submit(() -> {
            for (Page p : page.page().context().pages()) {
                if (p != page.page() && (p == opened || opened == null && "about:blank".equals(p.url()))) {
                    p.close();
                }
            }
            return null;
        });
    }

    /** Records a request turned back; a navigation puts the cookies back as they were before the input. */
    private void stop(Kind kind, String url, String method, boolean navigation) {
        synchronized (stopped) {
            stopped.add(new Stopped(kind, url, method));
        }
        if (navigation) {
            // Once: a later load turned back is not this input's, and the jar may have changed since.
            restore(jar);
            jar = null;
        }
    }

    /** What judging a request fails it for; empty to let it go. A judgement that cannot be made lets nothing go. */
    private Optional<Kind> judged(String url, String method, boolean navigation) {
        try {
            return verdict(url, method, navigation);
        } catch (RuntimeException e) {
            return Optional.of(navigation ? Kind.EXIT : Kind.WRITE);
        }
    }

    /**
     * Puts the cookies back as {@code cookies} had them: a navigation turned back leaves nothing behind. A sign-in's
     * redirect sets a cookie on its way off the site, a new one each press (an OAuth state), and the jar grows until
     * the site refuses every request as too large.
     */
    private void restore(List<Cookie> cookies) {
        if (cookies == null) {
            return;
        }
        try {
            page.cookies(cookies);
        } catch (RuntimeException e) {
            // A jar that cannot be put back is left as it is: the navigation was still turned back.
        }
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
