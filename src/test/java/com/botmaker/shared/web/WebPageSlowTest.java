// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pinned Chromium on the fixture site: the home page's tree, a frame, a press, and the console error page. Slow:
 * the first run downloads Chromium (about 200 MB) into the cache. Run by {@code -Dgroups=slow -Dsurefire.excludedGroups=}.
 */
@Tag("slow")
class WebPageSlowTest {

    private static FixtureSite site;
    private static Chromium chromium;

    @BeforeAll
    static void start() throws Exception {
        site = new FixtureSite();
        Path binary = ChromiumInstall.install(Downloads.Progress.IGNORED)
                .orElseThrow(() -> new IllegalStateException("no Chromium for this platform, or its download failed"));
        chromium = Chromium.start(binary, false, List.of());
    }

    @AfterAll
    static void stop() {
        if (chromium != null) {
            chromium.close();
        }
        if (site != null) {
            site.close();
        }
    }

    @Test
    void theHomePagesTreeHasItsLinksAndButtonsByRoleAndName() {
        try (WebPage page = chromium.newPage()) {
            assertTrue(page.open(site.url("/")).ok());
            WebTree tree = page.tree();
            WebNode cases = tree.find(Role.LINK, "Case studies").getFirst();
            assertEquals("nav-cases", cases.domId());
            assertEquals(site.url("/case-studies"), cases.href());
            assertTrue(cases.laidOut(), cases.line());
            WebNode start = tree.find(Role.BUTTON, "Get started").getFirst();
            assertEquals("cta-start", start.testId());
            assertEquals(1, tree.find(Role.HEADING, "Agents that play").size(), tree.outline());
            assertEquals(1, tree.find(Role.NAVIGATION, "Main").size(), tree.outline());
            assertEquals(site.url("/"), page.url());
        }
    }

    @Test
    void aFrameIsAJpegOfTheViewport() {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/"));
            byte[] frame = page.frame();
            assertTrue(frame.length > 1000);
            assertEquals((byte) 0xFF, frame[0]);
            assertEquals((byte) 0xD8, frame[1]);
        }
    }

    @Test
    void aPressChangesThePageAndTheChangeIsSettled() {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/"));
            page.click(page.tree().find(Role.BUTTON, "Get started").getFirst());
            assertTrue(page.settled());
            assertTrue(page.tree().nodes().stream().anyMatch(n -> n.name().equals("Started")),
                    page.tree().outline());
        }
    }

    @Test
    void aPageThatNeverStopsAnimatingStillSettlesAndItsAnswerToAPressCounts() {
        try (WebPage page = chromium.newPage()) {
            assertTrue(page.open(site.url("/animated")).settled(), "a spinner and a ticker are an animation");
            page.click(page.tree().find(Role.BUTTON, "Ask").getFirst());
            assertTrue(page.settled());
            assertTrue(page.tree().nodes().stream().anyMatch(n -> n.name().equals("Answered")),
                    page.tree().outline());
        }
    }

    @Test
    void aLinkPressNavigates() {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/"));
            page.click(page.tree().find(Role.LINK, "Case studies").getFirst());
            page.settled();
            assertEquals(site.url("/case-studies"), page.url());
        }
    }

    @Test
    void theConsoleErrorAndAMissingPageAreRead() {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/console-error"));
            List<ConsoleMessage> console = page.console();
            assertTrue(console.stream().anyMatch(m -> m.level() == ConsoleMessage.Level.ERROR
                    && m.text().contains("fixture: something broke")), console.toString());
            assertTrue(page.console().isEmpty(), "read once");

            page.open(site.url("/missing"));
            List<RequestFailure> failures = page.failures();
            assertTrue(failures.stream().anyMatch(f -> f.status() == 404 && f.url().endsWith("/missing")),
                    failures.toString());
            assertFalse(page.failures().stream().anyMatch(f -> f.status() == 404), "read once");
        }
    }
}
