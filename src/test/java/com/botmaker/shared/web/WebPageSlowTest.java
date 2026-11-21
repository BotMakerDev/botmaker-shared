// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        if (!ChromiumInstall.install(Downloads.Progress.IGNORED)) {
            throw new IllegalStateException("no Chromium for this platform, or its download failed");
        }
        chromium = Chromium.start(false, List.of());
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
    void aPageRefusedForTooManyCookiesIsLoadedAgainWithoutThem() {
        try (WebPage page = chromium.newPage()) {
            for (int i = 0; i < 6; i++) {
                assertTrue(page.open(site.url("/crowded")).ok());
                assertEquals("Crowded", page.evaluate("document.title"), "load " + i);
            }
            assertTrue(site.crowdedRefusals() > 0, "the site did refuse the grown jar");
        }
    }

    @Test
    void marksAreOutlinedOutsideTheTreeAndTakePressesFromNothing() {
        try (WebPage page = chromium.newPage()) {
            assertTrue(page.open(site.url("/")).ok());
            WebTree before = page.tree();
            WebNode start = before.find(Role.BUTTON, "Get started").getFirst();
            page.mark(before.interactive().stream().filter(WebNode::laidOut)
                    .map(n -> new WebPage.Mark(n.box(), n.name(), n.backendId() == start.backendId())).toList());
            String drawn = "document.getElementById('__botmaker_marks')?.childElementCount ?? 0";
            assertTrue(((Number) page.evaluate(drawn)).intValue() > 1);
            assertEquals(before.outline(), page.tree().outline(), "the marks are hidden from the tree");
            page.click(start);
            assertTrue(page.settled());
            assertTrue(page.tree().nodes().stream().anyMatch(n -> n.name().equals("Started")), "a press goes through");
            page.unmark();
            assertEquals(0, ((Number) page.evaluate(drawn)).intValue());
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
    void theWholePageIsRenderedBelowTheViewportToo() throws Exception {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/"));
            page.evaluate("void document.body.appendChild(Object.assign(document.createElement('div'),"
                    + "{style:'height:3000px'}))");
            BufferedImage whole = ImageIO.read(new ByteArrayInputStream(page.whole()));
            BufferedImage frame = ImageIO.read(new ByteArrayInputStream(page.frame()));
            assertEquals(frame.getWidth(), whole.getWidth(), "as wide as the viewport");
            assertTrue(whole.getHeight() > 3000, "as tall as the page: " + whole.getHeight());
        }
    }

    /** nunu.ai: a section fades in once scrolled into view, so the whole shot scrolls down first, then back. */
    @Test
    void whatShowsOnlyOnceScrolledToIsShownOnTheWholePage() throws Exception {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/reveal"));
            BufferedImage whole = ImageIO.read(new ByteArrayInputStream(page.whole()));
            // #low starts 60 px of header and 3000 px of margin down, 400 px tall, red once seen.
            int shown = whole.getRGB(100, 3060 + 200) & 0xFFFFFF;
            assertTrue(shown != 0xFFFFFF, "the section shows, not the page's white: " + Integer.toHexString(shown));
            assertEquals(0, ((Number) page.evaluate("window.scrollY")).intValue(), "back where it was");
        }
    }

    @Test
    void aRecordedPageLeavesAVideo(@TempDir Path dir) throws Exception {
        WebPage page = chromium.newPage(dir);
        try (page) {
            page.open(site.url("/"));
            page.click(page.tree().find(Role.BUTTON, "Get started").getFirst());
            page.settled();
        }
        Path video = page.video().orElseThrow();
        assertEquals(dir, video.getParent());
        assertTrue(video.toString().endsWith(".webm"), video.toString());
        assertTrue(Files.size(video) > 10_000, "size " + Files.size(video));
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
    void aPageLikeNunuAiSettlesSoonAfterAPressAndWaitsForItsLateAnswer() throws Exception {
        try (WebPage page = chromium.newPage()) {
            assertTrue(page.open(site.url("/typed")).settled(),
                    "a streaming video, workers' scripts and a staggered reveal are not waited for");
            page.click(page.tree().find(Role.BUTTON, "Ask").getFirst());
            // The typewriter's rests leave a quiet half second soon after the press.
            assertTrue(page.settled(WebPage.QUIET, Duration.ofSeconds(3)), "a typewriter rests between words");
            // The answer shows 700 ms after the press: whether a settle outlasts it depends on where in the
            // typewriter's round the press fell, so it is read once it shows, as a caller waiting for it does.
            long start = System.nanoTime();
            while (page.tree().nodes().stream().noneMatch(n -> n.name().equals("Answered"))
                    && System.nanoTime() - start < Duration.ofSeconds(2).toNanos()) {
                Thread.sleep(50);
            }
            assertTrue(page.tree().nodes().stream().anyMatch(n -> n.name().equals("Answered")),
                    page.tree().outline());
        }
    }

    /** A page whose load event never comes (an image that hangs) is read once the load wait's cap passed. */
    @Test
    void aPageWhoseLoadNeverEndsIsReadAfterTheCap() {
        try (WebPage page = chromium.newPage()) {
            long start = System.nanoTime();
            assertTrue(page.open(site.url("/hung")).ok());
            long took = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(took < WebPage.LOAD_CAP.plusSeconds(2).toMillis(), "open took " + took + " ms");
            assertEquals(1, page.tree().find(Role.HEADING, "Hung").size(), page.tree().outline());
        }
    }

    /** A settle after a press waits a short quiet, not the half second it did: the late answer is still read. */
    @Test
    void aPressOnTheTypedPageIsAnsweredFasterThanBefore() throws Exception {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/typed"));
            long start = System.nanoTime();
            page.click(page.tree().find(Role.BUTTON, "Ask").getFirst());
            page.settled();
            long settled = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(settled < 1500, "settled after " + settled + " ms");
            while (page.tree().nodes().stream().noneMatch(n -> n.name().equals("Answered"))
                    && System.nanoTime() - start < Duration.ofSeconds(2).toNanos()) {
                Thread.sleep(50);
            }
            assertTrue(page.tree().nodes().stream().anyMatch(n -> n.name().equals("Answered")),
                    "answered within 2 s: " + page.tree().outline());
        }
    }

    /** Two links of one name: the second one pressed is the second one, not its twin in the nav. */
    @Test
    void anIndexedElementIsPressedNotItsTwin() {
        try (WebPage page = chromium.newPage()) {
            page.open(site.url("/"));
            List<WebNode> cases = page.tree().find(Role.LINK, "Case studies");
            assertEquals(2, cases.size(), page.tree().outline());
            page.click(cases.get(1));
            page.settled();
            assertEquals(site.url("/case-studies?from=footer"), page.url());
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
