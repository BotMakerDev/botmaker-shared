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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the transparent-background (alpha-as-mask) matching path: a template with a fully transparent
 * border must be located by its opaque object alone, ignoring whatever sits behind the transparent pixels.
 * A plain (opaque) version of the same template — its transparent border baked to a solid colour that does
 * <em>not</em> appear around the object in the scene — must score strictly lower, proving the mask matters.
 */
class MaskedMatchTest {

    private static final int OBJ_W = 40, OBJ_H = 30, BORDER = 12;
    private static final int TPL_W = OBJ_W + 2 * BORDER, TPL_H = OBJ_H + 2 * BORDER;

    @BeforeAll
    static void loadNative() {
        OpenCvNative.ensureLoaded();   // Imgcodecs.imread below touches the native before OpencvManager does
    }

    /** Deterministic per-pixel colours for the opaque object (so template and scene copies are identical). */
    private static int objRgb(int x, int y) {
        Random rnd = new Random(31L * y + x);
        return rnd.nextInt(0xFFFFFF);
    }

    /** Transparent-border template (ARGB): opaque object centred in a fully transparent, lime-coloured border. */
    private static BufferedImage transparentTemplate() {
        BufferedImage img = new BufferedImage(TPL_W, TPL_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < TPL_H; y++) {
            for (int x = 0; x < TPL_W; x++) {
                boolean inside = x >= BORDER && x < BORDER + OBJ_W && y >= BORDER && y < BORDER + OBJ_H;
                if (inside) img.setRGB(x, y, 0xFF000000 | objRgb(x - BORDER, y - BORDER));
                else img.setRGB(x, y, 0x0000FF00);   // alpha 0 → ignored; lime RGB proves it's masked out
            }
        }
        return img;
    }

    /** The same template flattened opaque: the transparent border becomes solid lime. */
    private static BufferedImage opaqueTemplate() {
        BufferedImage img = new BufferedImage(TPL_W, TPL_H, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < TPL_H; y++) {
            for (int x = 0; x < TPL_W; x++) {
                boolean inside = x >= BORDER && x < BORDER + OBJ_W && y >= BORDER && y < BORDER + OBJ_H;
                img.setRGB(x, y, inside ? objRgb(x - BORDER, y - BORDER) : 0x0000FF00);
            }
        }
        return img;
    }

    /** Noise scene with just the opaque object pasted at (objX,objY) — no lime border around it. */
    private static BufferedImage scene(int objX, int objY) {
        BufferedImage bg = new BufferedImage(TPL_W + 200, TPL_H + 160, BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(7);
        for (int y = 0; y < bg.getHeight(); y++) {
            for (int x = 0; x < bg.getWidth(); x++) bg.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        }
        for (int y = 0; y < OBJ_H; y++) {
            for (int x = 0; x < OBJ_W; x++) bg.setRGB(objX + x, objY + y, objRgb(x, y));
        }
        return bg;
    }

    @Test
    void transparentTemplateMatchesByObjectIgnoringBackground(@TempDir Path dir) throws IOException {
        // Object pasted at (objX,objY) ⇒ the full template's top-left match is BORDER px up-and-left of it.
        int objX = 90, objY = 70;
        Path png = dir.resolve("obj.png");
        ImageIO.write(transparentTemplate(), "png", png.toFile());

        Mat masked = Imgcodecs.imread(png.toFile().getAbsolutePath(), Imgcodecs.IMREAD_UNCHANGED);
        Mat opaque = OpencvManager.bufferedImageToMat(opaqueTemplate());
        Mat bg = OpencvManager.bufferedImageToMat(scene(objX, objY));
        try {
            assertEquals(4, masked.channels(), "IMREAD_UNCHANGED must keep the alpha channel");

            RawMatch m = OpencvManager.findBestMatch(masked, bg, false, 0.8);
            assertNotNull(m, "the object is present, so the masked template must be found");
            assertEquals(objX - BORDER, m.x());
            assertEquals(objY - BORDER, m.y());
            assertTrue(m.score() > 0.9, "masked match over the object alone should score near 1.0, was " + m.score());

            // The opaque version drags its lime border into the score, so it matches worse at the same spot.
            double opaqueScore = OpencvManager.scoreAround(opaque, bg, false, objX - BORDER, objY - BORDER, 2);
            assertTrue(opaqueScore < m.score(),
                    "opaque (lime border) should score below masked: opaque=" + opaqueScore + " masked=" + m.score());
        } finally {
            masked.release();
            opaque.release();
            bg.release();
        }
    }
}
