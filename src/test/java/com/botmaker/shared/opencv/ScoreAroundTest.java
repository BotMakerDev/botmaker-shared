package com.botmaker.shared.opencv;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link OpencvManager#scoreAround} — the engine {@code ImageFinder.findCompare} relies on to
 * re-score a competing template at a location on the same frame. Fully synthetic (no fixture image,
 * no display), so these always run.
 */
class ScoreAroundTest {

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** A deterministic, non-uniform "button" patch so template correlation is well defined. */
    private static BufferedImage patch(int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Structured gradient + a little noise: distinct, non-flat, reproducible.
                int r = clamp((x * 255 / w) + rnd.nextInt(20));
                int g = clamp((y * 255 / h) + rnd.nextInt(20));
                int b = clamp(((x + y) * 127 / (w + h)) + rnd.nextInt(20));
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /** Noise canvas with {@code tpl} pasted at (offX, offY). */
    private static BufferedImage backgroundWith(BufferedImage tpl, int offX, int offY) {
        BufferedImage bg = new BufferedImage(tpl.getWidth() + 300, tpl.getHeight() + 200, BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(7);
        for (int y = 0; y < bg.getHeight(); y++) {
            for (int x = 0; x < bg.getWidth(); x++) {
                bg.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        bg.getGraphics().drawImage(tpl, offX, offY, null);
        return bg;
    }

    /**
     * A visually-similar but distinguishable ("greyed-out") variant: desaturate toward luminance and
     * add per-pixel noise. Non-affine, so TM_CCOEFF_NORMED (invariant to a single global
     * brightness/contrast scale) scores it genuinely lower than the exact template.
     */
    private static BufferedImage greyed(BufferedImage src, long seed) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(seed);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                int n = rnd.nextInt(61) - 30;
                out.setRGB(x, y, (clamp((r + lum) / 2 + n) << 16)
                        | (clamp((g + lum) / 2 + n) << 8) | clamp((b + lum) / 2 + n));
            }
        }
        return out;
    }

    @Test
    void exactTemplateScoresNearOneAtItsLocation() {
        BufferedImage tpl = patch(80, 40, 1);
        int offX = 130, offY = 90;
        Mat t = OpencvManager.bufferedImageToMat(tpl);
        Mat bg = OpencvManager.bufferedImageToMat(backgroundWith(tpl, offX, offY));
        try {
            double score = OpencvManager.scoreAround(t, bg, false, offX, offY, 4);
            assertTrue(score > 0.95, "exact template at its own location should score near 1.0, was " + score);
        } finally {
            t.release();
            bg.release();
        }
    }

    @Test
    void goodBeatsGreyedDistractorAtSameLocationByMargin() {
        BufferedImage good = patch(80, 40, 1);
        int offX = 100, offY = 70;
        Mat g = OpencvManager.bufferedImageToMat(good);
        Mat bad = OpencvManager.bufferedImageToMat(greyed(good, 11));
        Mat bg = OpencvManager.bufferedImageToMat(backgroundWith(good, offX, offY));
        try {
            double goodScore = OpencvManager.scoreAround(g, bg, false, offX, offY, 4);
            double badScore = OpencvManager.scoreAround(bad, bg, false, offX, offY, 4);
            assertTrue(goodScore > badScore + 0.05,
                    "active template must beat greyed variant by a margin: good=" + goodScore + " bad=" + badScore);
        } finally {
            g.release();
            bad.release();
            bg.release();
        }
    }

    @Test
    void returnsNegativeWhenWindowSmallerThanTemplate() {
        BufferedImage tpl = patch(80, 40, 1);
        Mat t = OpencvManager.bufferedImageToMat(tpl);
        Mat bg = OpencvManager.bufferedImageToMat(backgroundWith(tpl, 0, 0));
        try {
            double score = OpencvManager.scoreAround(t, bg, false, bg.cols() - 2, bg.rows() - 2, 4);
            assertEquals(-1.0, score, 1e-9);
        } finally {
            t.release();
            bg.release();
        }
    }
}
