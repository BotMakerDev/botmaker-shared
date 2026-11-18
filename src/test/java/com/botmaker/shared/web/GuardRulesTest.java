// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guard's rules without a browser: scope, presses, consent, robots, pace and budget. */
class GuardRulesTest {

    // --- Scope

    @Test
    void theScopeIsTheRegistrableDomainAndEverySubdomain() {
        Scope nunu = Scope.of(URI.create("https://app.nunu.ai/start"));
        assertEquals("nunu.ai", nunu.domain());
        assertTrue(nunu.contains("https://nunu.ai/"));
        assertTrue(nunu.contains("http://s.nunu.ai:8080/x"));
        assertTrue(nunu.contains("https://WWW.NUNU.AI./"));
        assertFalse(nunu.contains("https://notnunu.ai/"));
        assertFalse(nunu.contains("https://nunu.ai.evil.test/"));
        assertFalse(nunu.contains("https://api.workos.com/"));
        assertFalse(nunu.contains("mailto:hi@nunu.ai"));
        assertFalse(nunu.contains("not a url"));
    }

    @Test
    void thePublicSuffixListDecidesAndAPrivateSuffixKeepsSitesApart() {
        assertEquals("bar.co.uk", Scope.of(URI.create("https://foo.bar.co.uk/")).domain());
        Scope pages = Scope.of(URI.create("https://me.github.io/"));
        assertEquals("me.github.io", pages.domain());
        assertFalse(pages.contains("https://you.github.io/"));
        Scope bare = Scope.of(URI.create("https://github.io/"));
        assertTrue(bare.exact());
        assertTrue(bare.contains("https://github.io/x"));
        assertFalse(bare.contains("https://you.github.io/"));
        assertFalse(Scope.of(URI.create("https://co.uk/")).contains("https://bar.co.uk/"));
    }

    @Test
    void anUnlistedSuffixLocalhostAndAnAddressHaveTheirOwnScope() {
        Scope site = Scope.of(URI.create("http://www.site.test:8000/"));
        assertEquals("site.test", site.domain());
        assertTrue(site.contains("http://site.test/"));
        assertFalse(site.contains("http://elsewhere.test/"));
        assertEquals("localhost", Scope.of(URI.create("http://www.localhost:3000/")).domain());
        assertTrue(Scope.of(URI.create("http://localhost:3000/")).contains("http://app.localhost/"));
        Scope ip = Scope.of(URI.create("http://127.0.0.1:9000/"));
        assertTrue(ip.contains("http://127.0.0.1:1234/x"));
        assertFalse(ip.contains("http://127.0.0.2/"));
    }

    // --- Presses

    private static WebNode node(int index, int parent, List<Integer> children, Role role, String name) {
        return new WebNode(index, parent, children, role, name, "", Set.of(), "", "", "", null, index + 1);
    }

    @Test
    void aSubmitButtonAndAButtonInASendingFormAreRefused() {
        WebNode go = node(1, 0, List.of(), Role.BUTTON, "Go");
        assertEquals(Presses.Reason.SUBMIT,
                Presses.press(go, Optional.of(new Presses.Facts(true, true, "/api"))).orElseThrow().reason());
        Presses.Refusal form = Presses.press(go, Optional.of(new Presses.Facts(false, true, "/api/save")))
                .orElseThrow();
        assertEquals(Presses.Reason.FORM, form.reason());
        assertEquals("Inside a form that sends: /api/save", form.line());
        assertEquals(Presses.Reason.FORM, Presses.press(go, Optional.of(new Presses.Facts(false, true, "")))
                .orElseThrow().reason(), "a form with no action sends to its own page");
        assertTrue(Presses.press(go, Optional.of(new Presses.Facts(false, true, "#"))).isEmpty());
        assertTrue(Presses.press(go, Optional.of(new Presses.Facts(false, true, "javascript:void(0)"))).isEmpty());
        assertTrue(Presses.press(go, Optional.of(new Presses.Facts(false, false, ""))).isEmpty());
    }

    @Test
    void aNodeWhoseElementCannotBeReadIsRefused() {
        assertEquals(Presses.Reason.UNREAD,
                Presses.press(node(1, 0, List.of(), Role.BUTTON, "Next"), Optional.empty()).orElseThrow().reason());
        assertEquals(Presses.Reason.FORM, Presses.Reason.fromId("form"));
        assertEquals(Presses.Reason.UNKNOWN, Presses.Reason.fromId("later"));
        assertEquals(Guard.Kind.UNKNOWN, Guard.Kind.fromId(null));
    }

    @Test
    void aNameHoldingAWordOfTheListIsRefusedAsAWholeWord() {
        for (String name : List.of("Buy now", "Pay", "Checkout", "Sign  up free", "SUBSCRIBE", "Send message",
                "Delete account", "Book a demo", "Order", "Check out")) {
            assertEquals(Presses.Reason.WORD, Presses.word(name).orElseThrow().reason(), name);
        }
        for (String name : List.of("PayPal", "Repay", "Bookmarks", "Border", "Sign in", "Talk to us", "Get started")) {
            assertTrue(Presses.word(name).isEmpty(), name);
        }
        assertEquals("sign up", Presses.word("Sign up").orElseThrow().detail());
    }

    @Test
    void enterInAFieldIsRefusedAndElsewhereIsNot() {
        assertEquals(Presses.Reason.ENTER,
                Presses.key(node(1, 0, List.of(), Role.TEXTBOX, "Email"), "Enter").orElseThrow().reason());
        assertTrue(Presses.key(node(1, 0, List.of(), Role.SEARCHBOX, "Search"), "Enter").isPresent());
        assertTrue(Presses.key(node(1, 0, List.of(), Role.TEXTBOX, "Email"), "Tab").isEmpty());
        assertTrue(Presses.key(node(1, 0, List.of(), Role.LINK, "Docs"), "Enter").isEmpty());
    }

    // --- Consent

    private static WebTree dialog(Role role, String name, String... buttons) {
        List<WebNode> nodes = new ArrayList<>();
        List<Integer> kids = new ArrayList<>();
        for (int i = 0; i < buttons.length; i++) {
            kids.add(i + 3);
        }
        nodes.add(node(0, -1, List.of(1), Role.UNKNOWN, "page"));
        nodes.add(node(1, 0, List.of(2), role, name));
        List<Integer> withText = new ArrayList<>(List.of(3 + buttons.length));
        withText.addAll(0, kids);
        nodes.add(node(2, 1, withText, Role.UNKNOWN, ""));
        for (int i = 0; i < buttons.length; i++) {
            nodes.add(node(i + 3, 2, List.of(), Role.BUTTON, buttons[i]));
        }
        nodes.add(node(3 + buttons.length, 2, List.of(), Role.UNKNOWN, "We use cookies to improve the site."));
        return new WebTree(nodes);
    }

    @Test
    void aConsentDialogGetsItsRejectButton() {
        Consent.Answer nunu = Consent.of(dialog(Role.DIALOG, "", "Customize", "Deny", "Allow all"));
        assertEquals(Consent.Kind.REJECT, nunu.kind());
        assertEquals("Deny", nunu.button().orElseThrow().name());
        Consent.Answer both = Consent.of(dialog(Role.REGION, "Cookie banner", "Reject", "Reject all", "Accept all"));
        assertEquals("Reject all", both.button().orElseThrow().name());
        assertEquals("Necessary only", Consent.of(dialog(Role.DIALOG, "Privacy", "Accept", "Necessary only"))
                .button().orElseThrow().name());
    }

    @Test
    void aConsentDialogWithNoWayToRejectIsAStopAndOtherDialogsAreNone() {
        Consent.Answer stop = Consent.of(dialog(Role.DIALOG, "Cookies", "Settings", "Accept all"));
        assertEquals(Consent.Kind.STOP, stop.kind());
        assertEquals("Cookies", stop.dialog());
        assertTrue(stop.button().isEmpty());
        assertEquals(Consent.Kind.NONE, Consent.of(dialog(Role.DIALOG, "Video", "Play", "Close")).kind());
        WebTree disabled = dialog(Role.DIALOG, "Cookies", "Reject all", "Accept all");
        List<WebNode> nodes = new ArrayList<>(disabled.nodes());
        WebNode reject = nodes.get(3);
        nodes.set(3, new WebNode(reject.index(), reject.parent(), reject.children(), reject.role(), reject.name(), "",
                Set.of(WebState.DISABLED), "", "", "", null, reject.backendId()));
        assertEquals(Consent.Kind.STOP, Consent.of(new WebTree(nodes)).kind(), "a disabled reject is no way out");
        assertEquals(Consent.Kind.NONE, Consent.of(new WebTree(List.of(node(0, -1, List.of(), Role.UNKNOWN, ""))))
                .kind());
    }

    // --- Robots, pace, budget

    /** A clock whose sleeps pass at once, keeping each. */
    private static final class Fake implements Pace.Clock {
        long now = 1_000_000_000L;
        final List<Duration> slept = new ArrayList<>();

        @Override
        public long nanos() {
            return now;
        }

        @Override
        public void sleep(Duration duration) {
            slept.add(duration);
            now += duration.toNanos();
        }
    }

    private static Robots robots(int status, String text, AtomicInteger fetches) {
        return new Robots(Robots.userAgent("1.2.3"), uri -> {
            fetches.incrementAndGet();
            return new Robots.Fetched(status, text == null ? null : text.getBytes(StandardCharsets.UTF_8), "text/plain");
        });
    }

    @Test
    void robotsIsFetchedOncePerOriginAndItsGroupForBotMakerWins() {
        AtomicInteger fetches = new AtomicInteger();
        Robots robots = robots(200, """
                User-agent: *
                Disallow: /

                User-agent: BotMaker
                Disallow: /private
                Allow: /private/open
                Disallow: /*.pdf$
                """, fetches);
        assertTrue(robots.allows(URI.create("https://a.test/")));
        assertFalse(robots.allows(URI.create("https://a.test/private/x")));
        assertTrue(robots.allows(URI.create("https://a.test/private/open/y")));
        assertFalse(robots.allows(URI.create("https://a.test/doc/file.pdf")));
        assertFalse(robots.allowsNothing(URI.create("https://a.test/")));
        assertEquals(1, fetches.get());
        robots.allows(URI.create("https://b.a.test/"));
        assertEquals(2, fetches.get());
        assertEquals("BotMaker/1.2.3 (+discovery, read-only)", robots.userAgent());
    }

    @Test
    void disallowEverythingNoFileAndNoAnswerAreReadAsRfc9309Says() {
        AtomicInteger fetches = new AtomicInteger();
        assertTrue(robots(200, "User-agent: *\nDisallow: /\n", fetches).allowsNothing(URI.create("https://a.test/")));
        assertTrue(robots(404, null, fetches).allows(URI.create("https://a.test/any")));
        assertTrue(robots(503, null, fetches).allowsNothing(URI.create("https://a.test/")));
        assertTrue(robots(0, null, fetches).allowsNothing(URI.create("https://a.test/")));
        assertTrue(robots(301, null, fetches).allows(URI.create("https://a.test/any")), "an unfollowed redirect");
    }

    @Test
    void aCrawlDelayOfThreeSpacesLoadsThreeSecondsApartAndNeverSpeedsThePace() throws Exception {
        Robots robots = robots(200, "User-agent: *\nCrawl-delay: 3\n", new AtomicInteger());
        Fake clock = new Fake();
        Pace pace = new Pace(clock);
        pace.slowTo(robots.crawlDelay(URI.create("https://a.test/")).orElseThrow());
        List<Long> loads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pace.load();
            loads.add(clock.now);
        }
        assertEquals(Duration.ofSeconds(3).toNanos(), loads.get(1) - loads.get(0));
        assertEquals(Duration.ofSeconds(3).toNanos(), loads.get(2) - loads.get(1));
        pace.slowTo(Duration.ofMillis(100));
        assertEquals(Duration.ofSeconds(3), pace.loadGap());
        assertEquals(Optional.empty(), robots(200, "User-agent: *\nAllow: /\n", new AtomicInteger())
                .crawlDelay(URI.create("https://a.test/")));
    }

    @Test
    void actionsAreASecondApartAndALoadCountsAsOne() throws Exception {
        Fake clock = new Fake();
        Pace pace = new Pace(clock);
        pace.action();
        long first = clock.now;
        pace.action();
        assertEquals(Duration.ofSeconds(1).toNanos(), clock.now - first);
        clock.now += Duration.ofSeconds(5).toNanos();
        pace.action();
        assertEquals(1, clock.slept.size());
        long before = clock.now;
        pace.load();
        assertEquals(Duration.ofSeconds(1).toNanos(), clock.now - before);
    }

    @Test
    void theBudgetEndsAtItsLoadsOrItsTime() {
        Fake clock = new Fake();
        Budget budget = new Budget(2, Duration.ofMinutes(30), clock);
        assertTrue(budget.load());
        assertTrue(budget.load());
        assertFalse(budget.load());
        assertEquals(Budget.Limit.LOADS, budget.out().orElseThrow());
        assertEquals("2 page loads", budget.line(Budget.Limit.LOADS));
        Budget timed = new Budget(200, Duration.ofMinutes(30), clock);
        clock.now += Duration.ofMinutes(30).toNanos();
        assertFalse(timed.load());
        assertEquals(Budget.Limit.TIME, timed.out().orElseThrow());
        assertEquals("30 min", timed.line(Budget.Limit.TIME));
        assertEquals(0, timed.spent());
    }
}
