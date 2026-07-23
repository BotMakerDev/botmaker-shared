package com.botmaker.shared.opencv;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the template-matching engine by embedding a known patch into synthetic backgrounds, so the
 * expected match location is known exactly. Deterministic, and needs no display.
 *
 * <p>The template used to be loaded from {@code src/main/resources/images/accept_button.png} behind an
 * {@code assumeTrue(Files.exists(...))}. That file does not exist in either module, so the assumption
 * aborted the class and <b>every test here silently reported as skipped</b> — a matcher test suite that
 * had not run in a long time. It now generates its own patch: nothing about template matching needs a
 * photograph of a button, and a test that cannot be skipped by a missing file cannot rot that way again.
 */
class OpencvManagerTest {

    private static BufferedImage template;

    @BeforeAll
    static void load() {
        template = noise(96, 48, 1234);
    }

    /** Fills an image with deterministic pseudo-random noise (non-uniform, so correlation is defined). */
    private static BufferedImage noise(int width, int height, long seed) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        return img;
    }

    /** Background of noise with the template pasted at the given offset. */
    private static BufferedImage backgroundWithTemplate(int offsetX, int offsetY) {
        BufferedImage bg = noise(template.getWidth() + 400, template.getHeight() + 300, 7);
        bg.getGraphics().drawImage(template, offsetX, offsetY, null);
        return bg;
    }

    @Test
    void bufferedImageToMatPreservesDimensions() {
        Mat mat = OpencvManager.bufferedImageToMat(template);
        try {
            assertEquals(template.getWidth(), mat.cols());
            assertEquals(template.getHeight(), mat.rows());
            assertEquals(3, mat.channels());
            assertFalse(mat.empty());
        } finally {
            mat.release();
        }
    }

    @Test
    void findBestMatchLocatesEmbeddedTemplate() {
        int offX = 120, offY = 90;
        Mat tpl = OpencvManager.bufferedImageToMat(template);
        Mat bg = OpencvManager.bufferedImageToMat(backgroundWithTemplate(offX, offY));
        try {
            RawMatch m = OpencvManager.findBestMatch(tpl, bg, false, 0.9);

            assertNotNull(m, "the template was embedded, so it must be found");
            assertEquals(offX, m.x());
            assertEquals(offY, m.y());
            assertEquals(template.getWidth(), m.width());
            assertEquals(template.getHeight(), m.height());
            assertTrue(m.score() > 0.95, "exact paste should score near 1.0, was " + m.score());
        } finally {
            tpl.release();
            bg.release();
        }
    }

    @Test
    void findBestMatchWorksInGrayscale() {
        int offX = 60, offY = 40;
        Mat tpl = OpencvManager.bufferedImageToMat(template);
        Mat bg = OpencvManager.bufferedImageToMat(backgroundWithTemplate(offX, offY));
        try {
            RawMatch m = OpencvManager.findBestMatch(tpl, bg, true, 0.9);

            assertNotNull(m);
            assertEquals(offX, m.x());
            assertEquals(offY, m.y());
        } finally {
            tpl.release();
            bg.release();
        }
    }

    @Test
    void findBestMatchReturnsNullWhenAbsent() {
        Mat tpl = OpencvManager.bufferedImageToMat(template);
        Mat bg = OpencvManager.bufferedImageToMat(noise(template.getWidth() + 400, template.getHeight() + 300, 99));
        try {
            // Unrelated noise contains no copy of the template; a high threshold must reject any spurious peak.
            assertNull(OpencvManager.findBestMatch(tpl, bg, false, 0.95));
        } finally {
            tpl.release();
            bg.release();
        }
    }

    @Test
    void findBestMatchReturnsNullWhenBackgroundSmallerThanTemplate() {
        Mat tpl = OpencvManager.bufferedImageToMat(template);
        Mat bg = OpencvManager.bufferedImageToMat(noise(10, 10, 1));
        try {
            assertNull(OpencvManager.findBestMatch(tpl, bg, false, 0.9));
        } finally {
            tpl.release();
            bg.release();
        }
    }

    @Test
    void findMultipleMatchesReturnsOnePerOccurrence() {
        Mat tpl = OpencvManager.bufferedImageToMat(template);
        // Two well-separated copies of the template on a noise canvas.
        BufferedImage canvas = noise(template.getWidth() * 2 + 300, template.getHeight() + 200, 3);
        canvas.getGraphics().drawImage(template, 20, 20, null);
        canvas.getGraphics().drawImage(template, template.getWidth() + 200, 120, null);
        Mat bg = OpencvManager.bufferedImageToMat(canvas);
        try {
            List<RawMatch> matches = OpencvManager.findMultipleMatches(tpl, bg, false, 0.9);

            assertEquals(2, matches.size(), "non-maximal suppression should yield one match per occurrence");
            assertTrue(matches.stream().allMatch(m -> m.score() > 0.9));
        } finally {
            tpl.release();
            bg.release();
        }
    }
}
