// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.Video;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;

import java.awt.Point;
import java.awt.Rectangle;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>One tab of a {@link Chromium}</b>: it opens a URL, waits for the page to load and settle, reads the page's tree,
 * a frame, its console and its failed requests, and presses or types into a node through Playwright's input, which
 * goes into the renderer, never the user's cursor.
 *
 * <p>Settled is the page doing nothing: no request in flight (an image or a script for at most {@link #LOAD_CAP}) and
 * no DOM mutation for
 * a short quiet while. Mutations reach BotMaker through a function a small observer calls, at most once per 50 ms; the
 * observer draws nothing.
 */
public final class WebPage implements AutoCloseable {

    /** A longer quiet, for a caller that waits out a page's late answer. */
    public static final Duration QUIET = Duration.ofMillis(500);
    /** How long nothing must happen before a page counts as settled, by {@link #settled()}. */
    public static final Duration SHORT_QUIET = Duration.ofMillis(200);
    /** How long a settle waits at most. */
    public static final Duration SETTLE_MAX = Duration.ofSeconds(10);
    /**
     * How long a load waits for the page's {@code load} event once its document is in, and how long a request may stay
     * in flight before a settle stops waiting for it: an image or a script that hangs holds neither for longer.
     */
    static final Duration LOAD_CAP = Duration.ofSeconds(3);
    /** How long Playwright waits for an element to take a press or a text before the press is made at its centre. */
    private static final Duration ACTIONABLE = Duration.ofSeconds(2);
    /** The attribute a press marks its element with, for Playwright to find what the tree names by backend id. */
    private static final String PRESSED = "data-botmaker-press";

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
     * (nunu.ai's landing video, 2026-11-20). Playwright's resource types.
     */
    private static final Set<String> LONG_LIVED = Set.of("eventsource", "websocket", "media");
    /** A page refused for its request's headers: 431, and 494 as nginx and Vercel answer. */
    private static final Set<Integer> TOO_LARGE = Set.of(431, 494);

    /** How long a command's answer is waited for where a missing one is read as nothing. */
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(30);

    private final BrowserThread thread;
    private final Page page;
    private final String targetId;
    private final PageCdp session;
    /** Each request waited for, with when a settle stops waiting for it ({@link System#nanoTime()}). */
    private final Map<Request, Long> inFlight = new ConcurrentHashMap<>();
    /**
     * What a page loads beside its answer, which a settle waits for {@link #LOAD_CAP} at most: an image or a script
     * that hangs. A document, an XHR or a fetch may be the answer itself (a sign-in's POST), so it is waited for as long
     * as a settle lasts, and let go after {@link #FORGOTTEN} only so the map does not grow for the tab's life.
     */
    private static final Set<String> CAPPED = Set.of("image", "font", "stylesheet", "script", "texttrack", "manifest");
    private static final Duration FORGOTTEN = Duration.ofMinutes(1);
    /** The host that refused the tab's page as sending too many headers, waiting for {@link #settled}; else empty. */
    private final AtomicReference<String> crowded = new AtomicReference<>("");
    /** The HTTP status the tab's page was last loaded with; 0 before any load. */
    private volatile int documentStatus;
    /** The video a recorded tab saved on its close; null before, or when not recorded. */
    private volatile Path video;
    private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private final List<ConsoleMessage> console = new ArrayList<>();
    private final List<RequestFailure> failures = new ArrayList<>();
    /** The token of the next element a press marks with {@link #PRESSED}. */
    private final AtomicLong presses = new AtomicLong();

    /**
     * Made on the {@link BrowserThread}, which every Playwright call of {@code page} goes through. The page's network,
     * console and changes are heard through Playwright; the tree, frames and node boxes are still read through CDP.
     */
    WebPage(BrowserThread thread, Page page, PageCdp session) {
        this.thread = thread;
        this.page = page;
        this.session = session;
        // The tab's target id, which is its main frame's id too.
        this.targetId = session.call("Target.getTargetInfo").path("targetInfo").path("targetId").asText();
        listen();
        session.call("DOM.enable");
        session.call("Accessibility.enable");
        page.exposeFunction(BINDING, args -> {
            touch();
            return null;
        });
        page.addInitScript(OBSERVER);
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
     * Opens {@code url} in this tab: once its document is in, the page's {@code load} is waited for at most
     * {@link #LOAD_CAP} (none for a move within the same document, such as a {@code #part}), then a settle of
     * {@link #SHORT_QUIET}. A page whose load never comes is still read.
     */
    public Opened open(String url) {
        long start = System.nanoTime();
        String error = thread.call(() -> {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(SETTLE_MAX.toMillis()));
            } catch (TimeoutError slow) {
                // Its document still coming after the longest settle: what is there is read.
            } catch (PlaywrightException failed) {
                return reason(failed);
            }
            loaded();
            return "";
        });
        if (!error.isEmpty()) {
            return new Opened(error, false);
        }
        Duration left = SETTLE_MAX.minusNanos(System.nanoTime() - start);
        return new Opened("", settled(SHORT_QUIET, left.isNegative() ? Duration.ZERO : left));
    }

    /** Waits on the browser thread for the page's {@code load}, at most {@link #LOAD_CAP}. */
    private void loaded() {
        try {
            page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(LOAD_CAP.toMillis()));
        } catch (PlaywrightException hungOrGone) {
            // A load that never comes (an image that hangs), or a page closed meanwhile: the settle decides.
        }
    }

    /** A navigation's failure as Chromium names it ({@code net::ERR_ABORTED}), else the message's first line. */
    private static String reason(PlaywrightException failed) {
        String message = failed.getMessage() == null ? "" : failed.getMessage();
        Matcher net = NET_ERROR.matcher(message);
        if (net.find()) {
            return net.group();
        }
        String line = message.lines().findFirst().orElse("").strip();
        return line.isEmpty() ? "the page did not load" : line;
    }

    private static final Pattern NET_ERROR = Pattern.compile("net::ERR_[A-Z_]+");

    /** Waits for the page to settle: {@link #SHORT_QUIET} of nothing, at most {@link #SETTLE_MAX}. */
    public boolean settled() {
        return settled(SHORT_QUIET, SETTLE_MAX);
    }

    /**
     * Waits until no request is in flight and nothing changed for {@code quiet}, or {@code max} passed; an image, a
     * script or a font in flight longer than {@link #LOAD_CAP} is no longer waited for. A page the site refused as sending too many headers
     * is loaded again once, with that site's cookies cleared: a site that sets a new cookie on each page (a sign-in's
     * verifier) refuses a visitor who reads many pages fast.
     */
    public boolean settled(Duration quiet, Duration max) {
        boolean settled = settledOnce(quiet, max);
        String host = crowded.getAndSet("");
        if (host.isEmpty()) {
            return settled;
        }
        clearCookies(host);
        // The reload is activity itself: its first request may be heard only after the settle below starts.
        touch();
        thread.run(() -> {
            try {
                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(SETTLE_MAX.toMillis()));
            } catch (PlaywrightException slowOrFailed) {
                // What the reload brought, or did not, is settled below all the same.
            }
            loaded();
        });
        return settledOnce(quiet, max);
    }

    /** Deletes every cookie the browser sends to {@code host}. */
    private void clearCookies(String host) {
        List<Cookie> kept = new ArrayList<>();
        for (Cookie cookie : cookies()) {
            String domain = cookie.domain == null ? "" : cookie.domain;
            String bare = domain.startsWith(".") ? domain.substring(1) : domain;
            if (!host.equals(bare) && !host.endsWith("." + bare)) {
                kept.add(cookie);
            }
        }
        cookies(kept);
    }

    /** The cookies of the tab's context: its own jar, which no other tab shares. */
    List<Cookie> cookies() {
        return thread.call(() -> page.context().cookies());
    }

    /**
     * Puts {@code cookies} in place of every cookie of the tab's context: they are set first, then each other cookie is
     * cleared by its name, domain and path, so a cookie the browser refuses leaves the jar as it was, not empty.
     */
    void cookies(List<Cookie> cookies) {
        thread.run(() -> {
            BrowserContext context = page.context();
            List<Cookie> before = context.cookies();
            if (!cookies.isEmpty()) {
                context.addCookies(cookies);
            }
            Set<String> wanted = new HashSet<>();
            for (Cookie cookie : cookies) {
                wanted.add(key(cookie));
            }
            for (Cookie cookie : before) {
                if (!wanted.contains(key(cookie))) {
                    context.clearCookies(new BrowserContext.ClearCookiesOptions().setName(cookie.name)
                            .setDomain(cookie.domain).setPath(cookie.path));
                }
            }
        });
    }

    /** What tells a cookie from another of the jar: its name, domain and path. */
    private static String key(Cookie cookie) {
        return cookie.name + "\n" + cookie.domain + "\n" + cookie.path;
    }

    private boolean settledOnce(Duration quiet, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (true) {
            long now = System.nanoTime();
            // A request past its wait is let go: an image or a script that hangs, after the cap.
            inFlight.values().removeIf(until -> now - until >= 0);
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
        return thread.call(page::url);
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

    /**
     * What the viewport shows, as JPEG.
     *
     * @throws IllegalStateException when the page went, or did not render within {@link #SHOT_TIMEOUT} (a webfont
     *                               that never loads holds Playwright's shot)
     */
    public byte[] frame() {
        try {
            return thread.call(() -> page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG)
                    .setQuality(80).setTimeout(SHOT_TIMEOUT.toMillis())));
        } catch (PlaywrightException e) {
            throw new IllegalStateException("no frame of the page: " + e.getMessage(), e);
        }
    }

    /**
     * The page's part inside {@code box}, as PNG, rendered by Chromium even where the viewport does not reach: a
     * tree's box ({@link #tree}) is the viewport's, so the page's scroll is added to clip it from the whole page.
     * A box not wholly in the viewport is scrolled to first, rested at and settled as {@link #whole} does, then the
     * page scrolled back: a section a site reveals once scrolled to (nunu.ai's fade in) rendered unseen was its
     * background (2026-11-22). Empty when the box lies outside the page.
     */
    public byte[] picture(Rectangle box) {
        if (!(evaluate(VIEW) instanceof List<?> at) || at.size() != 4) return new byte[0];
        double view = number(at.get(1));
        int scrollX = (int) number(at.get(2));
        int scrollY = (int) number(at.get(3));
        double top = box.y + scrollY;
        boolean seen = box.y >= 0 && box.y + box.height <= view;
        try {
            if (!seen) {
                // Its middle at the viewport's, as a reader scrolls to it; an int: Playwright passes no long.
                evaluate("y => window.scrollTo(window.scrollX, y)", (int) Math.max(0, top - (view - box.height) / 2));
                rest(VIEW_REST);
                settled(SHORT_QUIET, WHOLE_SETTLE);
            }
            // Full page, so the clip is the page's wherever the page now is.
            return shot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG).setFullPage(true).setClip(
                    box.x + scrollX, top, box.width, box.height));
        } finally {
            if (!seen) evaluate("([x, y]) => window.scrollTo(x, y)", List.of(scrollX, scrollY));
        }
    }

    /**
     * The whole page from its top, as PNG: a screen's shot, where a long page shows all it holds (the user's call,
     * 2026-11-21). The page is first scrolled down a viewport at a time, resting {@link #VIEW_REST} at each, and back
     * where it was, each step bounded in Java, then settled for {@link #WHOLE_SETTLE} at most: a section a site reveals
     * once scrolled to (nunu.ai's fade in) and the lazy images the pass started show, which a render below the
     * viewport left blank. A section hidden again once scrolled away from does not. Then Playwright shoots the full
     * page, a bar that stays on the screen (fixed or sticky) drawn once, at the top. As wide as a frame, at most
     * {@link #WHOLE_HEIGHT} CSS pixels tall; empty when the page went meanwhile.
     */
    public byte[] whole() {
        if (!(evaluate(VIEW) instanceof List<?> at) || at.size() != 4) return new byte[0];
        // Every bound is Java's: the page's own scripts may have replaced what a bound in its JavaScript would call.
        double width = Math.min(Chromium.WIDTH, number(at.get(0)));
        double view = Math.max(100, number(at.get(1)));
        try {
            for (int tile = 0; tile <= WHOLE_HEIGHT / view && tile * view < height(); tile++) {
                // An int: Playwright passes no long.
                evaluate("y => window.scrollTo(0, y)", (int) (tile * view));
                rest(VIEW_REST);
            }
        } finally {
            evaluate("([x, y]) => window.scrollTo(x, y)", List.of((int) number(at.get(2)), (int) number(at.get(3))));
        }
        settled(SHORT_QUIET, WHOLE_SETTLE);
        // Measured once settled: an image the pass started may have grown the page.
        double tall = height();
        if (width < 1 || tall < 1) return new byte[0];
        return shot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG).setFullPage(true)
                .setClip(0, 0, width, tall));
    }

    /** The page's height, at most {@link #WHOLE_HEIGHT}. */
    private double height() {
        return Math.min(WHOLE_HEIGHT, number(evaluate("document.documentElement.scrollHeight")));
    }

    private static void rest(Duration time) {
        try {
            Thread.sleep(time.toMillis());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    /** The tallest {@link #whole} renders, in CSS pixels: Chromium's own limit on a texture. */
    public static final int WHOLE_HEIGHT = 16_384;

    /** How long {@link #whole}'s scroll pass rests at each view: what shows on a scroll starts in that time. */
    private static final Duration VIEW_REST = Duration.ofMillis(150);

    /** The longest {@link #whole} waits for the page to settle after its scroll pass: an animation does not hold it. */
    private static final Duration WHOLE_SETTLE = Duration.ofMillis(1500);

    /** The longest a shot waits for the page to render: Playwright waits for its webfonts, which may hang. */
    private static final Duration SHOT_TIMEOUT = Duration.ofSeconds(5);

    /** The viewport's width and height and the page's scroll, which {@link #whole} goes back to. */
    private static final String VIEW = "[document.documentElement.clientWidth, window.innerHeight, window.scrollX, "
            + "window.scrollY]";

    /** A screenshot by {@code options}, empty when the page went, the clip lay outside it or it timed out. */
    private byte[] shot(Page.ScreenshotOptions options) {
        try {
            return thread.call(() -> page.screenshot(options.setTimeout(SHOT_TIMEOUT.toMillis())));
        } catch (PlaywrightException goneOrOutside) {
            return new byte[0];
        }
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /**
     * The HTTP status the tab's page was last loaded with, as its server answered (a 404, a 431 error page); 0 before
     * any load. A page that changes its address in place keeps its load's.
     */
    public int documentStatus() {
        return documentStatus;
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
     * Presses {@code node} with the left button: its element, found by the node's backend id, is pressed by Playwright
     * once it is visible, still and takes the press; an element that does not within {@link #ACTIONABLE} (an overlay
     * over it, a frame's element) is pressed at its box's centre, scrolled into view first. A link to another app
     * ({@link #handedOff}) is not pressed: Chromium would hand it to the desktop, which opened the user's mail app at
     * each of nunu.ai's {@code mailto:} links (2026-11-21).
     *
     * @throws IllegalStateException when it has no box to press
     */
    public void click(WebNode node) {
        if (handedOff(node.href())) return;
        boolean pressed = onElement(node, e -> {
            // A trial waits until the element takes the press and presses nothing: a press that then fails or waits
            // too long is never made twice by the fallback.
            e.click(new ElementHandle.ClickOptions().setTrial(true).setTimeout(ACTIONABLE.toMillis()));
            try {
                e.click(new ElementHandle.ClickOptions().setNoWaitAfter(true).setTimeout(ACTIONABLE.toMillis()));
            } catch (PlaywrightException madeOrGone) {
                // Pressed, then its page moved on; or gone at once. Either way it is not pressed again.
            }
        });
        if (!pressed) {
            clickAt(centre(node));
        }
        touch();
    }

    /**
     * Runs {@code work} on {@code node}'s element in Playwright, on the browser thread: the element is marked with
     * {@link #PRESSED} through CDP, found by it, and unmarked after. False when it has no element of the tab's own
     * document or {@code work} failed or timed out, so the caller does it another way.
     */
    private boolean onElement(WebNode node, Consumer<ElementHandle> work) {
        if (node.backendId() == 0) {
            return false;
        }
        String token = String.valueOf(presses.incrementAndGet());
        return thread.call(() -> {
            if (!marked(node, token)) {
                return false;
            }
            ElementHandle element = null;
            try {
                element = page.querySelector("[" + PRESSED + "='" + token + "']");
                if (element == null) {
                    return false;
                }
                work.accept(element);
                return true;
            } catch (PlaywrightException notActionable) {
                return false;
            } finally {
                if (element == null) {
                    // Marked but not found (a frame's element): the mark comes off through CDP.
                    marked(node, null);
                } else {
                    try {
                        element.evaluate("e => e.removeAttribute('" + PRESSED + "')");
                        element.dispose();
                    } catch (PlaywrightException goneWithItsPage) {
                        // A press that navigated took the element and its mark with it.
                    }
                }
            }
        });
    }

    /** Marks {@code node}'s element with {@link #PRESSED}{@code =token}, unmarks it for null; false for no element. */
    private boolean marked(WebNode node, String token) {
        try {
            String object = session.call("DOM.resolveNode", Map.of("backendNodeId", node.backendId()))
                    .path("object").path("objectId").asText("");
            if (object.isEmpty()) {
                return false;
            }
            JsonNode answer = session.call("Runtime.callFunctionOn", Map.of("objectId", object, "returnByValue", true,
                    "functionDeclaration", "function() { if (this.nodeType !== 1) return false; "
                            + (token == null ? "this.removeAttribute('" + PRESSED + "')"
                                    : "this.setAttribute('" + PRESSED + "', '" + token + "')") + "; return true; }"));
            session.send("Runtime.releaseObject", Map.of("objectId", object));
            return answer.path("result").path("value").asBoolean(false);
        } catch (PageCdp.CdpException goneOrDetached) {
            return false;
        }
    }

    /** Whether {@code href} names no page but another app's address, such as {@code mailto:} or {@code tel:}. */
    public static boolean handedOff(String href) {
        int colon = href.indexOf(':');
        if (href.isEmpty() || colon < 0) return false;
        String scheme = href.substring(0, colon).toLowerCase(Locale.ROOT);
        return !WEB_SCHEMES.contains(scheme);
    }

    /** The schemes a page is at; any other a link names is handed to another app. */
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https", "about", "blob", "data", "file",
            "javascript");

    /**
     * Puts {@code text} in {@code node} in place of what it held, as Playwright fills a field once it is editable; an
     * element it cannot fill (no field, a frame's) is focused and {@code text} typed where its caret is.
     */
    public void type(WebNode node, String text) {
        if (!onElement(node, e -> e.fill(text, new ElementHandle.FillOptions().setTimeout(ACTIONABLE.toMillis())))) {
            session.call("DOM.focus", Map.of("backendNodeId", node.backendId()));
            insert(text);
        }
        touch();
    }

    /**
     * Presses the left button at {@code at}, in viewport CSS pixels: a press no node of the tree names.
     *
     * <p>Every input counts as activity, so a settle after it waits at least its quiet: an answer the page shows a
     * moment later, or a change whose report is still on its way, is not missed.
     */
    public void clickAt(Point at) {
        thread.run(() -> page.mouse().click(at.x, at.y));
        touch();
    }

    /** Turns the wheel at {@code at} by {@code dx}, {@code dy} CSS pixels: positive scrolls right and down. */
    public void wheel(Point at, int dx, int dy) {
        thread.run(() -> {
            page.mouse().move(at.x, at.y);
            page.mouse().wheel(dx, dy);
        });
        touch();
    }

    /** Types {@code text} into whatever has the focus, as one insertion. */
    public void insert(String text) {
        thread.run(() -> page.keyboard().insertText(text));
        touch();
    }

    /**
     * Presses and releases the key {@code key}, a DOM key name ({@code Enter}, {@code Escape}, {@code Tab},
     * {@code ArrowDown}, {@code a}, {@code " "}), as Playwright's keyboard does: a letter types, Enter submits.
     */
    public void key(String key) {
        thread.run(() -> page.keyboard().press(" ".equals(key) ? "Space" : key));
        touch();
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
        } catch (PageCdp.CdpException goneOrDetached) {
            return Optional.empty();
        }
    }

    /** One part outlined for a person watching: its box in viewport CSS pixels, its label, whether it is pressed. */
    public record Mark(Rectangle box, String label, boolean pressed) {}

    /**
     * Outlines {@code marks} over the page for a person watching the browser, in place of any outlined before: a layer
     * hidden from the accessibility tree that takes no press, so only a read of the frame would see it. Take it off with
     * {@link #unmark} before the page is read again.
     */
    public void mark(List<Mark> marks) {
        List<Map<String, Object>> drawn = new ArrayList<>();
        for (Mark mark : marks) {
            Rectangle b = mark.box();
            if (b == null || b.isEmpty()) continue;
            drawn.add(Map.of("x", b.x, "y", b.y, "w", b.width, "h", b.height, "t", mark.label(),
                    "c", mark.pressed() ? "#dc2626" : "#2563eb"));
        }
        evaluate(MARK, drawn);
    }

    /** Takes off what {@link #mark} outlined; nothing when nothing is. */
    public void unmark() {
        evaluate("document.getElementById('" + MARKS_ID + "')?.remove()");
    }

    /**
     * {@code expression}'s value on the page, by value (a string, number, boolean, list or map); null when it has none,
     * when it threw, or when the page navigated away or closed meanwhile. A function is called with no argument.
     */
    public Object evaluate(String expression) {
        return evaluate(expression, null);
    }

    /** {@code script} on the page, a function given {@code arg} or an expression; null as {@link #evaluate(String)}. */
    private Object evaluate(String script, Object arg) {
        try {
            return thread.call(() -> page.evaluate(script, arg));
        } catch (PlaywrightException gone) {
            return null;
        }
    }

    /** {@code node}'s box once scrolled into view, empty when it has no DOM node or is not laid out. */
    public Optional<Rectangle> inView(WebNode node) {
        if (node.backendId() == 0) return Optional.empty();
        try {
            session.call("DOM.scrollIntoViewIfNeeded", Map.of("backendNodeId", node.backendId()));
            return Optional.ofNullable(orNull(session.send("DOM.getBoxModel", Map.of("backendNodeId",
                    node.backendId())))).map(b -> rectangle(b.path("model").path("border"))).filter(b -> !b.isEmpty());
        } catch (PageCdp.CdpException goneOrDetached) {
            return Optional.empty();
        }
    }

    private static final String MARKS_ID = "__botmaker_marks";
    /** Draws each mark as an outlined box with its label above, on one fixed layer over the whole viewport. */
    private static final String MARK = """
            marks => {
              document.getElementById('%1$s')?.remove();
              const layer = document.createElement('div');
              layer.id = '%1$s';
              layer.setAttribute('aria-hidden', 'true');
              layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647';
              for (const m of marks) {
                const box = document.createElement('div');
                box.style.cssText = `position:absolute;left:${m.x}px;top:${m.y}px;width:${m.w}px;height:${m.h}px;`
                    + `outline:2px solid ${m.c};outline-offset:1px;border-radius:3px;background:${m.c}1f`;
                const label = document.createElement('span');
                label.textContent = m.t;
                label.style.cssText = `position:absolute;left:-2px;bottom:100%%;margin-bottom:3px;`
                    + `font:600 11px/15px system-ui,sans-serif;color:#fff;background:${m.c};padding:0 4px;`
                    + `border-radius:3px;white-space:nowrap;max-width:320px;overflow:hidden;text-overflow:ellipsis`;
                box.appendChild(label);
                layer.appendChild(box);
              }
              document.documentElement.appendChild(layer);
            }""".formatted(MARKS_ID);

    /** The tab's own DevTools commands and events, for what Playwright has no call of (the guard's, the tree's). */
    PageCdp session() {
        return session;
    }

    /**
     * Closes the tab with its context, and what it opened; a recorded tab ({@link Chromium#newPage(Path)}) saves its
     * video, which {@link #video()} then names.
     */
    @Override
    public void close() {
        try {
            thread.run(() -> {
                Video recording = page.video();
                page.context().close();
                if (recording != null) video = recording.path();
            });
        } catch (RuntimeException ignored) {
            // A closed browser closed its tabs, and lost a video it had not saved.
        }
    }

    /** The tab's video, once a recorded tab is closed; empty before, or for a tab not recorded. */
    public Optional<Path> video() {
        return Optional.ofNullable(video);
    }

    /** The Playwright page, called on {@link #thread()} only. */
    Page page() {
        return page;
    }

    /** The tab's target, which is its main frame's id too. */
    String targetId() {
        return targetId;
    }

    /** The thread every call of {@link #page()} is made on. */
    BrowserThread thread() {
        return thread;
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

    /** Hears the page through Playwright, on the browser thread: its requests, responses, console and errors. */
    private void listen() {
        page.onLoad(p -> touch());
        page.onRequest(request -> {
            // Only a request waited for is kept: one that stays open by design would hold every settle.
            if (!LONG_LIVED.contains(request.resourceType()) && ofAFrame(request)) {
                inFlight.put(request, System.nanoTime()
                        + (CAPPED.contains(request.resourceType()) ? LOAD_CAP : FORGOTTEN).toNanos());
            }
            touch();
        });
        page.onResponse(this::answered);
        page.onRequestFinished(this::done);
        page.onRequestFailed(request -> {
            String failure = request.failure();
            // A request the page or a navigation let go of is no failure of the site's.
            if (failure != null && !failure.contains("ERR_ABORTED")) {
                addFailure(new RequestFailure(request.url(), 0, failure));
            }
            done(request);
        });
        page.onConsoleMessage(message -> addConsole(new ConsoleMessage(ConsoleMessage.Level.fromId(message.type()),
                message.text(), where(message.location()))));
        page.onPageError(error -> addConsole(new ConsoleMessage(ConsoleMessage.Level.ERROR, error, "")));
    }

    /**
     * Whether a frame of the page made {@code request}: a worker's script and what a worker fetches have none, and
     * are not waited for. Waiting for a worker's script held every settle of nunu.ai, whose worker comes from a
     * {@code blob:} URL, to {@link #SETTLE_MAX} (2026-11-20).
     */
    private static boolean ofAFrame(Request request) {
        try {
            return request.frame() != null;
        } catch (PlaywrightException aWorkers) {
            return false;
        }
    }

    /** A response: the tab's own document gives {@link #documentStatus}, and a 431 the reload {@link #settled} makes. */
    private void answered(Response response) {
        int status = response.status();
        boolean document = false;
        try {
            document = response.request().isNavigationRequest() && response.frame() == page.mainFrame();
        } catch (PlaywrightException aWorkers) {
            // No frame: not the tab's document.
        }
        if (document) {
            documentStatus = status;
            if (TOO_LARGE.contains(status)) {
                try {
                    String host = URI.create(response.url()).getHost();
                    crowded.set(host == null ? "" : host);
                } catch (IllegalArgumentException odd) {
                    // A URL that does not parse names no host to clear.
                }
            }
        }
        if (status >= 400) {
            addFailure(new RequestFailure(response.url(), status, ""));
        }
        touch();
    }

    /** A console message's script, out of Playwright's {@code url:line:column}. */
    private static String where(String location) {
        return location == null ? "" : location.replaceFirst(":\\d+:\\d+$", "");
    }

    private void done(Request request) {
        inFlight.remove(request);
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
            return answer.get(ANSWER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
