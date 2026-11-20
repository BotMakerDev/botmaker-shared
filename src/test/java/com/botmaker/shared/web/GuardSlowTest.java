// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard in the pinned Chromium, on two fixture sites the browser reaches by name: {@code site.test} (and its
 * {@code www.} alias) and {@code elsewhere.test}, both on loopback through {@code --host-resolver-rules}. The other
 * site counts what reaches it: a way out never does. Slow, as {@link WebPageSlowTest}.
 */
@Tag("slow")
class GuardSlowTest {

    private static FixtureSite site;
    private static FixtureSite other;
    private static Chromium chromium;

    @BeforeAll
    static void start() throws Exception {
        site = new FixtureSite();
        other = new FixtureSite();
        String otherBase = "http://elsewhere.test:" + other.port();
        site.names(Map.of("OTHER", otherBase, "ACCOUNTS", "http://accounts.elsewhere.test:" + other.port(),
                "ALIAS", "http://www.site.test:" + site.port()));
        Path binary = ChromiumInstall.install(Downloads.Progress.IGNORED)
                .orElseThrow(() -> new IllegalStateException("no Chromium for this platform, or its download failed"));
        chromium = Chromium.start(binary, false, List.of("--host-resolver-rules=MAP site.test 127.0.0.1, "
                + "MAP *.site.test 127.0.0.1, MAP elsewhere.test 127.0.0.1, MAP *.elsewhere.test 127.0.0.1"));
    }

    @AfterAll
    static void stop() {
        if (chromium != null) {
            chromium.close();
        }
        for (FixtureSite s : new FixtureSite[] {site, other}) {
            if (s != null) {
                s.close();
            }
        }
    }

    private static String home() {
        return "http://site.test:" + site.port() + "/guard";
    }

    /** Robots read from the fixture by its loopback address, the name being the browser's alone. */
    private static Robots robots() {
        String agent = Robots.userAgent("test");
        Robots.Fetcher http = Robots.http(agent);
        return new Robots(agent, uri -> http.fetch(URI.create("http://127.0.0.1:" + uri.getPort() + uri.getPath())));
    }

    private static Guard guard(WebPage page) {
        return Guard.on(page, Scope.of(URI.create(home())), robots());
    }

    private static WebNode link(WebPage page, String name) {
        return page.tree().find(Role.LINK, name).getFirst();
    }

    private static WebNode button(WebPage page, String name) {
        return page.tree().find(Role.BUTTON, name).getFirst();
    }

    /** What the guard stopped once the page is quiet, waiting a little for its judging thread. */
    private static List<Guard.Stopped> stopped(WebPage page, Guard guard) throws InterruptedException {
        page.settled();
        List<Guard.Stopped> all = new ArrayList<>(guard.stopped());
        for (int i = 0; i < 20 && all.isEmpty(); i++) {
            Thread.sleep(50);
            all.addAll(guard.stopped());
        }
        return all;
    }

    @Test
    void aLinkOffTheSiteAndASignInWithGoogleAreExitsThatReachNothing() throws Exception {
        site.robots(null);
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            assertTrue(page.open(home()).ok());
            int jobs = other.hits("/jobs");
            int signIn = other.hits("/signin");
            assertTrue(guard.press(link(page, "Jobs board")).isEmpty());
            assertEquals(List.of(new Guard.Stopped(Guard.Kind.EXIT, "http://elsewhere.test:" + other.port() + "/jobs",
                    "GET")), stopped(page, guard));
            assertTrue(guard.press(link(page, "Sign in with Google")).isEmpty());
            List<Guard.Stopped> google = stopped(page, guard);
            assertEquals(1, google.size(), google.toString());
            assertEquals(Guard.Kind.EXIT, google.getFirst().kind());
            assertTrue(google.getFirst().url().startsWith("http://accounts.elsewhere.test:"), google.toString());
            assertEquals(jobs, other.hits("/jobs"));
            assertEquals(signIn, other.hits("/signin"));
            assertEquals(0, other.hits("/jobs"));
            assertTrue(other.hits("/pixel.png") > 0, "the page's own request to another host passes");
            assertEquals(home(), page.url());
        }
    }

    @Test
    void aNewTabAndAPopupAreGuardedAsTheTab() throws Exception {
        site.robots(null);
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            page.open(home());
            long before = pages();
            assertTrue(guard.press(link(page, "Shop in a new tab")).isEmpty());
            List<Guard.Stopped> tab = stopped(page, guard);
            assertEquals(List.of(new Guard.Stopped(Guard.Kind.EXIT, "http://elsewhere.test:" + other.port() + "/tab",
                    "GET")), tab);
            assertTrue(guard.press(link(page, "Chat with us")).isEmpty());
            List<Guard.Stopped> popup = stopped(page, guard);
            assertEquals(List.of(new Guard.Stopped(Guard.Kind.EXIT,
                    "http://elsewhere.test:" + other.port() + "/popup", "GET")), popup);
            assertEquals(0, other.hits("/tab"));
            assertEquals(0, other.hits("/popup"));
            // Each tab the guard turned back is closed, not left on about:blank.
            for (int i = 0; i < 40 && pages() > before; i++) {
                Thread.sleep(50);
            }
            assertEquals(before, pages());
        }
    }

    /** The browser's open pages. */
    private static long pages() {
        return StreamSupport.stream(chromium.cdp().browser().call("Target.getTargets").path("targetInfos")
                .spliterator(), false).filter(t -> t.path("type").asText().equals("page")).count();
    }

    @Test
    void aRedirectOffTheSiteIsAnExitWhereItLands() throws Exception {
        site.robots(null);
        site.redirect("http://elsewhere.test:" + other.port() + "/careers");
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            page.open(home());
            guard.press(link(page, "Careers"));
            List<Guard.Stopped> stopped = stopped(page, guard);
            assertEquals(List.of(new Guard.Stopped(Guard.Kind.EXIT,
                    "http://elsewhere.test:" + other.port() + "/careers", "GET")), stopped);
            assertEquals(0, other.hits("/careers"));
            assertTrue(site.hits("/redirect-out") > 0);
        }
    }

    @Test
    void aRedirectTurnedBackLeavesNoCookieBehind() throws Exception {
        site.robots(null);
        site.redirect("http://elsewhere.test:" + other.port() + "/careers");
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            page.open(home());
            // The visitor's own cookie stays as it was.
            page.session().call("Storage.setCookies", Map.of("cookies", List.of(Map.of("name", "kept", "value", "1",
                    "domain", "site.test", "path", "/", "httpOnly", true))));
            String before = page.session().call("Storage.getCookies").path("cookies").toString();
            assertTrue(before.contains("\"kept\""), before);
            for (int i = 0; i < 3; i++) {
                guard.press(link(page, "Careers"));
                stopped(page, guard);
            }
            assertTrue(site.hits("/redirect-out") >= 3);
            assertEquals(before, page.session().call("Storage.getCookies").path("cookies").toString(),
                    "each sign-in's state cookie is undone with its turned-back redirect");
        }
    }

    @Test
    void theWwwAliasIsOnTheSite() throws Exception {
        site.robots(null);
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            page.open(home());
            guard.press(link(page, "Case studies on www"));
            page.settled();
            assertEquals(List.of(), guard.stopped());
            assertEquals("http://www.site.test:" + site.port() + "/case-studies", page.url());
        }
    }

    @Test
    void pressesThatWouldWriteAreRefusedAndAWriteThatSlipsThroughIsFailed() throws Exception {
        site.robots(null);
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            page.open(home());
            assertEquals(Presses.Reason.FORM, guard.press(button(page, "Save draft")).orElseThrow().reason());
            assertEquals(Presses.Reason.SUBMIT, guard.press(button(page, "Go")).orElseThrow().reason());
            assertEquals(Presses.Reason.WORD, guard.press(button(page, "Subscribe")).orElseThrow().reason());
            assertTrue(guard.press(button(page, "Show more")).isEmpty());
            page.settled();
            assertEquals("Shown", page.session().call("Runtime.evaluate", Map.of("expression",
                    "document.getElementById('said').textContent", "returnByValue", true))
                    .path("result").path("value").asText(), "only the harmless press was made");

            int writes = site.writes();
            page.click(button(page, "Save draft"));
            List<Guard.Stopped> stopped = stopped(page, guard);
            assertEquals(List.of(new Guard.Stopped(Guard.Kind.WRITE, "http://site.test:" + site.port() + "/api/save",
                    "POST")), stopped);
            assertEquals(writes, site.writes());
            assertEquals(0, site.writes());
        }
    }

    @Test
    void aPathRobotsDisallowsIsNeverLoaded() throws Exception {
        site.robots("User-agent: *\nDisallow: /private\n");
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            page.open(home());
            guard.press(link(page, "Secret"));
            assertEquals(List.of(new Guard.Stopped(Guard.Kind.ROBOTS,
                    "http://site.test:" + site.port() + "/private/secret", "GET")), stopped(page, guard));
            assertEquals(0, site.hits("/private/secret"));
            assertEquals(home(), page.url());
        } finally {
            site.robots(null);
        }
    }

    @Test
    void disallowEverythingStopsAtTheStart() throws Exception {
        site.robots("User-agent: BotMaker\nDisallow: /\n");
        try (WebPage page = chromium.newPage(); Guard guard = guard(page)) {
            assertTrue(guard.robots().allowsNothing(URI.create(home())));
            int before = site.hits("/guard");
            assertFalse(page.open(home()).ok());
            assertEquals(Guard.Kind.ROBOTS, stopped(page, guard).getFirst().kind());
            assertEquals(before, site.hits("/guard"));
        } finally {
            site.robots(null);
        }
    }
}
