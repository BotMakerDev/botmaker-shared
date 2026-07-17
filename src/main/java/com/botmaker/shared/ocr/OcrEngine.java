package com.botmaker.shared.ocr;

import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * On-screen text recognition — the OCR core shared by both consumers (the SDK's {@code Text} bot facade
 * today, Studio editor features later). Runs an OpenCV preprocessing pass ({@link OcrPreprocessor}) then
 * Tesseract via Tess4J.
 *
 * <p><b>Thread-safety:</b> a Tess4J {@link Tesseract} instance is <em>not</em> thread-safe, and bots run
 * many threads (the same reason the SDK's {@code VisionContext} is thread-local). Each thread therefore
 * gets its own instance via a {@link ThreadLocal}; callers need no external synchronization.
 *
 * <p>A genuine native-load failure (e.g. missing {@code libtesseract} on Linux) surfaces as an
 * {@link UnsatisfiedLinkError} and is intentionally not caught, so it cannot masquerade as "no text".
 */
public final class OcrEngine {

    private OcrEngine() {}

    private static final ThreadLocal<ITesseract> TESSERACT = ThreadLocal.withInitial(OcrEngine::newTesseract);

    private static ITesseract newTesseract() {
        Tesseract t = new Tesseract();
        t.setDatapath(OcrNative.tessdataPath().toString());
        return t;
    }

    /** All recognized text of {@code img} as one string, at default options. Empty string on no text. */
    public static String text(BufferedImage img) {
        return text(img, OcrOptions.defaults());
    }

    /** All recognized text of {@code img} as one string, using {@code opts}. Empty string on no text. */
    public static String text(BufferedImage img, OcrOptions opts) {
        ITesseract tess = configure(opts);
        BufferedImage pre = OcrPreprocessor.process(img, opts);
        try {
            String out = tess.doOCR(pre);
            return out == null ? "" : out.trim();
        } catch (TesseractException e) {
            throw new IllegalStateException("OCR failed", e);
        }
    }

    /**
     * Recognizes {@code img} and returns one {@link TextResult} per word or line (per
     * {@link OcrOptions#level()}), each with its source-local bounding box and confidence. The boxes are
     * mapped back down through {@link OcrOptions#upscale()} so they are in the input image's coordinates.
     */
    public static List<TextResult> recognize(BufferedImage img, OcrOptions opts) {
        ITesseract tess = configure(opts);
        BufferedImage pre = OcrPreprocessor.process(img, opts);

        int iteratorLevel = opts.level() == TextResult.Level.LINE
                ? TessPageIteratorLevel.RIL_TEXTLINE
                : TessPageIteratorLevel.RIL_WORD;

        double scale = opts.upscale() > 0 ? opts.upscale() : 1.0;
        List<Word> words = tess.getWords(pre, iteratorLevel);
        List<TextResult> results = new ArrayList<>(words.size());
        for (Word word : words) {
            String text = word.getText() == null ? "" : word.getText().trim();
            if (text.isEmpty()) continue;
            Rectangle box = word.getBoundingBox();
            Rectangle local = new Rectangle(
                    (int) Math.round(box.x / scale),
                    (int) Math.round(box.y / scale),
                    (int) Math.round(box.width / scale),
                    (int) Math.round(box.height / scale));
            results.add(new TextResult(text, local, word.getConfidence(), opts.level()));
        }
        return results;
    }

    /** Applies the per-call {@code opts} to this thread's Tesseract instance and returns it. */
    private static ITesseract configure(OcrOptions opts) {
        ITesseract tess = TESSERACT.get();
        tess.setLanguage(opts.languages());
        tess.setPageSegMode(opts.pageSegMode());
        tess.setOcrEngineMode(opts.ocrEngineMode());
        // Empty string clears any whitelist from a previous call on this (reused) instance.
        tess.setVariable("tessedit_char_whitelist",
                opts.charWhitelist() == null ? "" : opts.charWhitelist());
        return tess;
    }
}
