package com.botmaker.shared.ocr;

/**
 * Immutable OCR tuning knobs. Start from {@link #defaults()} and derive variants with the {@code with*}
 * copy methods:
 *
 * <pre>{@code
 * OcrOptions opts = OcrOptions.defaults()
 *         .withLanguages("eng")
 *         .withUpscale(3.0)                 // small HUD font — upscale more
 *         .withBinarize(BinarizeMode.OTSU)
 *         .withInvert(true)                 // light text on a dark panel
 *         .withCharWhitelist("0123456789"); // a numeric counter
 * }</pre>
 *
 * @param languages    Tesseract language spec, e.g. {@code "eng"} or {@code "eng+chi_sim"}. Each language
 *                     must be bundled (see {@link OcrNative#BUNDLED_LANGUAGES}).
 * @param pageSegMode  Tesseract Page Segmentation Mode (PSM). {@code 3} = fully automatic (default);
 *                     {@code 7} = single line; {@code 8} = single word; {@code 6} = a uniform block.
 * @param ocrEngineMode Tesseract OCR Engine Mode (OEM). {@code 1} = LSTM only (default).
 * @param grayscale    convert to grayscale before recognition (usually helps).
 * @param upscale      linear upscale factor applied before recognition ({@code 1.0} = none). {@code 2–3}
 *                     markedly improves accuracy on small game fonts. Bounding boxes are mapped back down.
 * @param binarize     thresholding mode applied after grayscale — the main accuracy win for game text.
 * @param invert       invert after binarizing, so light-on-dark text becomes dark-on-light for Tesseract.
 * @param charWhitelist restrict recognition to these characters ({@code null}/empty = all).
 * @param level        granularity of {@link OcrEngine#recognize} results: {@link TextResult.Level#WORD}
 *                     or {@link TextResult.Level#LINE}.
 */
public record OcrOptions(
        String languages,
        int pageSegMode,
        int ocrEngineMode,
        boolean grayscale,
        double upscale,
        BinarizeMode binarize,
        boolean invert,
        String charWhitelist,
        TextResult.Level level) {

    /** How {@link OcrPreprocessor} turns a grayscale image into black-and-white for Tesseract. */
    public enum BinarizeMode {
        /** Leave the (grayscale) image as-is. */
        NONE,
        /** Global Otsu threshold — best for even lighting / flat UI panels. The default. */
        OTSU,
        /** Adaptive (local) threshold — better under uneven lighting / gradients. */
        ADAPTIVE
    }

    /** Sensible general-purpose defaults: English, automatic layout, grayscale + 2× upscale + Otsu. */
    public static OcrOptions defaults() {
        return new OcrOptions("eng", 3, 1, true, 2.0, BinarizeMode.OTSU, false, null, TextResult.Level.WORD);
    }

    public OcrOptions withLanguages(String languages) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withPageSegMode(int pageSegMode) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withOcrEngineMode(int ocrEngineMode) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withGrayscale(boolean grayscale) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withUpscale(double upscale) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withBinarize(BinarizeMode binarize) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withInvert(boolean invert) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withCharWhitelist(String charWhitelist) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }

    public OcrOptions withLevel(TextResult.Level level) {
        return new OcrOptions(languages, pageSegMode, ocrEngineMode, grayscale, upscale, binarize, invert, charWhitelist, level);
    }
}
