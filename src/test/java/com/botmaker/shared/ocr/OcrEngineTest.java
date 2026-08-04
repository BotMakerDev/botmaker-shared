package com.botmaker.shared.ocr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the whole OCR path — OpenCV native load, preprocessing, Tesseract — with no screen dependency
 * by rendering known text into a {@link BufferedImage} via Java2D and recognizing it back.
 */
class OcrEngineTest {

    /**
     * Windows is self-contained (Tess4J bundles the DLLs); a Linux box without system {@code libtesseract} has
     * no OCR to test. The binding loads its native lazily, and the failure is an {@code Error} that
     * {@link OcrEngine} deliberately does not catch — so probe for it here rather than let every test error.
     */
    private static boolean tesseractAvailable() {
        try {
            Class.forName("net.sourceforge.tess4j.TessAPI"); // initializes INSTANCE, which loads the library
            return true;
        } catch (Throwable noNative) { // UnsatisfiedLinkError, then NoClassDefFoundError on every later attempt
            return false;
        }
    }

    @BeforeEach
    void requireTesseract() {
        assumeTrue(tesseractAvailable(), "no system libtesseract on this machine");
    }

    /** Renders {@code text} as large black-on-white text into a fresh image. */
    private static BufferedImage render(String text) {
        BufferedImage img = new BufferedImage(600, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));
        g.drawString(text, 20, 95);
        g.dispose();
        return img;
    }

    @Test
    void readsRenderedText() {
        String recognized = OcrEngine.text(render("HELLO WORLD")).toUpperCase();
        assertTrue(recognized.contains("HELLO"), "expected HELLO in: '" + recognized + "'");
        assertTrue(recognized.contains("WORLD"), "expected WORLD in: '" + recognized + "'");
    }

    @Test
    void recognizeReturnsWordsWithPlausibleBoxes() {
        List<TextResult> words = OcrEngine.recognize(render("SCORE"), OcrOptions.defaults());
        assertFalse(words.isEmpty(), "expected at least one recognized word");

        TextResult first = words.stream()
                .filter(w -> w.text().toUpperCase().contains("SCORE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SCORE not among: " + words));

        // Box is in source-local (600x140) coords, not the upscaled space.
        assertTrue(first.bounds().width > 0 && first.bounds().height > 0, "box has size");
        assertTrue(first.bounds().x >= 0 && first.bounds().x < 600, "box x within image");
        assertTrue(first.bounds().y >= 0 && first.bounds().y < 140, "box y within image");
        assertTrue(first.confidence() > 0f, "confidence reported");
    }

    @Test
    void respectsCharWhitelist() {
        // A numeric HUD counter: whitelisting digits keeps letters from creeping in.
        OcrOptions digits = OcrOptions.defaults().withCharWhitelist("0123456789").withUpscale(3.0);
        String recognized = OcrEngine.text(render("12345"), digits);
        assertTrue(recognized.contains("12345"), "expected 12345 in: '" + recognized + "'");
    }
}
