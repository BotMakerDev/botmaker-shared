package com.botmaker.shared.opencv;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The score a template gets on a screen it is <em>not</em> on must be low — the property every threshold in
 * the SDK is built on, and the one that was missing.
 *
 * <p>The reported bug: every {@code find} came back at 0.89+ whether or not the object was there, so no
 * confidence threshold could tell present from absent. The cause was the alpha path — Studio captures
 * {@code TYPE_INT_ARGB} and reads back {@code IMREAD_UNCHANGED}, so every Studio template had four channels
 * and took masked {@code TM_CCORR_NORMED}, whose score over 8-bit BGR floors well above any usable threshold.
 *
 * <p>So the discriminating case here is the <b>fully-opaque template that still carries an alpha channel</b>:
 * exactly what Studio produces, and what used to score 0.89+ on empty screens.
 */
class AbsentTemplateTest {

    private static final int TPL_W = 48, TPL_H = 36;
    private static final int PRESENT_X = 120, PRESENT_Y = 80;
    /** Comfortably below the SDK's 0.8 default, and far below the 0.89+ the bug reported. */
    private static final double ABSENT_CEILING = 0.5;

    @BeforeAll
    static void loadNative() {
        OpenCvNative.ensureLoaded();
    }

    /** A deterministic, structured object — a gradient with a bright bar, not noise a scene might accidentally hit. */
    private static int objectRgb(int x, int y) {
        int r = (x * 255) / TPL_W;
        int g = (y * 255) / TPL_H;
        int b = (y > TPL_H / 2) ? 240 : 16;
        return (r << 16) | (g << 8) | b;
    }

    /** The object as a plain 3-channel template. */
    private static BufferedImage opaqueTemplate() {
        BufferedImage img = new BufferedImage(TPL_W, TPL_H, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < TPL_H; y++) {
            for (int x = 0; x < TPL_W; x++) img.setRGB(x, y, objectRgb(x, y));
        }
        return img;
    }

    /** The same object as Studio writes it: {@code TYPE_INT_ARGB}, every pixel opaque. */
    private static BufferedImage argbTemplate() {
        BufferedImage img = new BufferedImage(TPL_W, TPL_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < TPL_H; y++) {
            for (int x = 0; x < TPL_W; x++) img.setRGB(x, y, 0xFF000000 | objectRgb(x, y));
        }
        return img;
    }

    /** The same object with a genuinely transparent left half — the path that really does want a mask. */
    private static BufferedImage cutoutTemplate() {
        BufferedImage img = new BufferedImage(TPL_W, TPL_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < TPL_H; y++) {
            for (int x = 0; x < TPL_W; x++) {
                boolean opaque = x >= TPL_W / 2;
                img.setRGB(x, y, opaque ? (0xFF000000 | objectRgb(x, y)) : 0x0000FF00);
            }
        }
        return img;
    }

    /** A busy scene, with the object pasted in only when {@code present}. */
    private static BufferedImage scene(boolean present) {
        BufferedImage bg = new BufferedImage(TPL_W + 320, TPL_H + 240, BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(11);
        for (int y = 0; y < bg.getHeight(); y++) {
            for (int x = 0; x < bg.getWidth(); x++) bg.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        }
        if (present) {
            for (int y = 0; y < TPL_H; y++) {
                for (int x = 0; x < TPL_W; x++) bg.setRGB(PRESENT_X + x, PRESENT_Y + y, objectRgb(x, y));
            }
        }
        return bg;
    }

    /** Round-trips through a PNG so the Mat is built exactly the way the SDK builds one from a template file. */
    private static Mat readAsTemplate(BufferedImage img, Path dir, String name) throws IOException {
        Path png = dir.resolve(name);
        ImageIO.write(img, "png", png.toFile());
        return Imgcodecs.imread(png.toFile().getAbsolutePath(), Imgcodecs.IMREAD_UNCHANGED);
    }

    @Test
    void anOpaqueTemplateScoresLowWhenAbsentAndHighWhenPresent() {
        Mat template = OpencvManager.bufferedImageToMat(opaqueTemplate());
        Mat absent = OpencvManager.bufferedImageToMat(scene(false));
        Mat present = OpencvManager.bufferedImageToMat(scene(true));
        try {
            assertTrue(OpencvManager.findBest(template, absent, false).score() < ABSENT_CEILING,
                    "an absent object must not score like a present one");
            RawMatch hit = OpencvManager.findBest(template, present, false);
            assertTrue(hit.score() > 0.9, "a present object should score near 1.0, was " + hit.score());
            assertEquals(PRESENT_X, hit.x());
            assertEquals(PRESENT_Y, hit.y());
        } finally {
            template.release();
            absent.release();
            present.release();
        }
    }

    /** The reported case: Studio's ARGB capture, fully opaque, must behave exactly like the 3-channel one. */
    @Test
    void aFullyOpaqueArgbTemplateDoesNotTakeTheMaskedPath(@TempDir Path dir) throws IOException {
        Mat template = readAsTemplate(argbTemplate(), dir, "opaque-argb.png");
        Mat absent = OpencvManager.bufferedImageToMat(scene(false));
        Mat present = OpencvManager.bufferedImageToMat(scene(true));
        try {
            assertEquals(4, template.channels(), "IMREAD_UNCHANGED must keep the alpha channel");

            double miss = OpencvManager.findBest(template, absent, false).score();
            assertTrue(miss < ABSENT_CEILING,
                    "an opaque ARGB capture on a screen without the object must score low, was " + miss);
            assertTrue(OpencvManager.findBest(template, present, false).score() > 0.9,
                    "and must still find the object when it is there");
        } finally {
            template.release();
            absent.release();
            present.release();
        }
    }

    /** And the genuinely-masked path keeps the same property. */
    @Test
    void anAlphaCarryingTemplateAlsoScoresLowWhenAbsent(@TempDir Path dir) throws IOException {
        Mat template = readAsTemplate(cutoutTemplate(), dir, "cutout.png");
        Mat absent = OpencvManager.bufferedImageToMat(scene(false));
        Mat present = OpencvManager.bufferedImageToMat(scene(true));
        try {
            double miss = OpencvManager.findBest(template, absent, false).score();
            assertTrue(miss < ABSENT_CEILING,
                    "a masked template on a screen without the object must score low, was " + miss);
            RawMatch hit = OpencvManager.findBest(template, present, false);
            assertTrue(hit.score() > 0.9, "the opaque half is present, so it must be found: " + hit.score());
            assertEquals(PRESENT_X, hit.x(), "the match locates the whole template, not just its opaque half");
            assertEquals(PRESENT_Y, hit.y());
        } finally {
            template.release();
            absent.release();
            present.release();
        }
    }
}
