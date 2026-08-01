package com.botmaker.shared.opencv;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the colour engine's precision knobs: {@code tolerance} (CIELAB ΔE, colour precision) and the two
 * quantity gates, {@code minArea} (smallest connected blob) and {@code minCount} (matching pixels anywhere in
 * the frame).
 */
class ColorMatcherTest {

    private static final int W = 120, H = 100;

    private static BufferedImage filled(Color bg) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) img.setRGB(x, y, bg.getRGB());
        return img;
    }

    private static void paintRect(BufferedImage img, int x0, int y0, int w, int h, Color c) {
        for (int y = y0; y < y0 + h; y++)
            for (int x = x0; x < x0 + w; x++) img.setRGB(x, y, c.getRGB());
    }

    @Test
    void findsSolidPatchAndReportsItsGeometry() {
        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 20, 30, 40, 25, Color.RED);

        List<RawColorMatch> found = ColorMatcher.findClusters(img, Color.RED, 5.0, 4, 0);
        assertEquals(1, found.size(), "expected exactly one red cluster");

        RawColorMatch m = found.get(0);
        assertEquals(20, m.x());
        assertEquals(30, m.y());
        assertEquals(40, m.width());
        assertEquals(25, m.height());
        assertEquals(40 * 25, m.pixelCount());
        // Centroid of a solid rect is its centre.
        assertEquals(20 + 40 / 2.0, m.centroidX(), 1.0);
        assertEquals(30 + 25 / 2.0, m.centroidY(), 1.0);
    }

    @Test
    void separateClustersAreReturnedLargestFirst() {
        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 5, 5, 10, 10, Color.BLUE);     // 100 px
        paintRect(img, 60, 60, 20, 20, Color.BLUE);   // 400 px

        List<RawColorMatch> found = ColorMatcher.findClusters(img, Color.BLUE, 5.0, 4, 0);
        assertEquals(2, found.size());
        assertEquals(400, found.get(0).pixelCount(), "largest cluster must come first");
        assertEquals(100, found.get(1).pixelCount());
    }

    /** {@code n} isolated single pixels of {@code c}, spaced so 8-connectivity keeps every one its own blob. */
    private static void paintSpeckle(BufferedImage img, int n, Color c) {
        for (int i = 0; i < n; i++) {
            img.setRGB(2 + (i % 20) * 3, 2 + (i / 20) * 3, c.getRGB());
        }
    }

    @Test
    void theCountGateAndTheAreaFilterAnswerDifferentQuestions() {
        // 50 scattered single pixels: plenty of this colour on screen, but not one real patch of it. The two
        // gates disagree about this frame, which is the whole reason there are two.
        BufferedImage img = filled(Color.WHITE);
        paintSpeckle(img, 50, Color.GREEN);

        assertEquals(50, ColorMatcher.findClusters(img, Color.GREEN, 5.0, 1, 0).size(),
                "no gates: every speck is its own cluster");
        assertTrue(ColorMatcher.findClusters(img, Color.GREEN, 5.0, 10, 0).isEmpty(),
                "an area floor of 10 rejects a frame made entirely of 1-pixel blobs");
        assertEquals(50, ColorMatcher.findClusters(img, Color.GREEN, 5.0, 1, 40).size(),
                "50 matching pixels satisfies a count gate of 40, however they are clumped");
        assertTrue(ColorMatcher.findClusters(img, Color.GREEN, 5.0, 1, 60).isEmpty(),
                "50 matching pixels cannot satisfy a count gate of 60");
    }

    @Test
    void thePassingCountGateDoesNotPromiseThatAnythingSurvivesTheAreaFilter() {
        // The trap the javadoc calls out: minCount is measured on the mask, minArea on the blobs. Here the
        // count gate passes comfortably and the result is still empty — reading minCount as "the total area
        // of what comes back" would predict 50 pixels' worth of clusters.
        BufferedImage img = filled(Color.WHITE);
        paintSpeckle(img, 50, Color.GREEN);

        assertEquals(50, ColorMatcher.matchCount(img, Color.GREEN, 5.0));
        assertTrue(ColorMatcher.findClusters(img, Color.GREEN, 5.0, 10, 40).isEmpty());
    }

    @Test
    void matchCountIsTheNumberTheCountGateCompares() {
        // The editor shows this count to explain a threshold; if it were computed differently from the gate it
        // would advertise a value that then didn't match.
        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 10, 10, 30, 20, Color.RED);   // 600 px

        assertEquals(600, ColorMatcher.matchCount(img, Color.RED, 5.0));
        assertEquals(600 / (double) (W * H), ColorMatcher.coverage(img, Color.RED, 5.0), 1e-9,
                "coverage is that same count over the frame");
        assertFalse(ColorMatcher.findClusters(img, Color.RED, 5.0, 1, 600).isEmpty(), "the gate is inclusive");
        assertTrue(ColorMatcher.findClusters(img, Color.RED, 5.0, 1, 601).isEmpty());
    }

    @Test
    void minAreaRejectsSpeckleButKeepsTheRealPatch() {
        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 10, 10, 30, 30, Color.GREEN);  // the real patch
        img.setRGB(90, 90, Color.GREEN.getRGB());     // a single stray pixel
        img.setRGB(95, 20, Color.GREEN.getRGB());     // another

        // minArea=1 sees everything...
        assertEquals(3, ColorMatcher.findClusters(img, Color.GREEN, 5.0, 1, 0).size());
        // ...minArea=10 keeps only the genuine patch. This is location precision, not colour precision.
        List<RawColorMatch> strict = ColorMatcher.findClusters(img, Color.GREEN, 5.0, 10, 0);
        assertEquals(1, strict.size());
        assertEquals(900, strict.get(0).pixelCount());
    }

    @Test
    void toleranceIsPerceptualNotChannelwise() {
        // A near-red that differs from pure red by a small perceptual amount.
        Color nearRed = new Color(245, 12, 8);
        double dE = ColorMatcher.deltaE(Color.RED, nearRed);
        assertTrue(dE < 8, "expected a small perceptual distance, got ΔE=" + dE);

        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 10, 10, 20, 20, nearRed);

        assertTrue(ColorMatcher.findClusters(img, Color.RED, 10.0, 4, 0).isEmpty() == false,
                "a loose-enough tolerance must match the near-red patch");
        assertTrue(ColorMatcher.findClusters(img, Color.RED, 0.0, 4, 0).isEmpty(),
                "EXACT tolerance must not match a different colour");
    }

    @Test
    void toleranceSeparatesDistinctHuesAtTheSameLuminance() {
        // Red and green sit far apart in Lab even though a naive metric can confuse mid-tones.
        assertTrue(ColorMatcher.deltaE(Color.RED, Color.GREEN) > 50);

        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 10, 10, 20, 20, Color.GREEN);
        assertTrue(ColorMatcher.findClusters(img, Color.RED, 25.0, 4, 0).isEmpty(),
                "a green patch must not match red even at LOOSE tolerance");
    }

    @Test
    void inRangeMatchesAChannelBand() {
        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 10, 10, 20, 20, new Color(200, 30, 30));

        List<RawColorMatch> found = ColorMatcher.findClustersInRange(
                img, new Color(150, 0, 0), new Color(255, 80, 80), 4, 0);
        assertEquals(1, found.size());
        assertEquals(400, found.get(0).pixelCount());
    }

    @Test
    void coverageReportsTheMatchingFraction() {
        BufferedImage img = filled(Color.WHITE);
        paintRect(img, 0, 0, W, H / 4, Color.RED);   // exactly a quarter of the image

        double c = ColorMatcher.coverage(img, Color.RED, 5.0);
        assertEquals(0.25, c, 0.01);
    }

    @Test
    void gradientDoesNotDragTheMatchAcrossTheImage() {
        // A horizontal red→black gradient. A seed-relative metric must only match the genuinely-red end,
        // unlike a neighbour-relative flood which would walk the whole ramp.
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int r = 255 - (int) (255.0 * x / (W - 1));
                img.setRGB(x, y, new Color(r, 0, 0).getRGB());
            }

        List<RawColorMatch> found = ColorMatcher.findClusters(img, Color.RED, 10.0, 4, 0);
        assertEquals(1, found.size());
        RawColorMatch m = found.get(0);
        assertTrue(m.width() < W / 3,
                "tolerance must bound the match to the red end, but it spanned " + m.width() + " of " + W);
        assertEquals(0, m.x(), "the match should start at the fully-red edge");
    }
}
