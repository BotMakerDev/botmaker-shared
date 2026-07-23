package com.botmaker.shared.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Idempotent native-resource loading for the OCR stack. Two responsibilities, each run at most once:
 *
 * <ul>
 *   <li>{@link #ensureOpenCvLoaded()} — load the OpenPnP OpenCV native (used by {@link OcrPreprocessor}),
 *       delegated to {@link com.botmaker.shared.opencv.OpenCvNative} so the whole process has one loader.</li>
 *   <li>{@link #tessdataPath()} — Tesseract needs a real filesystem {@code datapath}, not a classpath
 *       resource, so the bundled {@code *.traineddata} are extracted to a temp dir on first use and that
 *       dir is handed to every {@code Tesseract} instance.</li>
 * </ul>
 *
 * <p>The Tesseract native itself is loaded lazily by Tess4J on the first {@code doOCR}/{@code getWords}
 * call. It is self-contained on Windows (Tess4J bundles the DLLs); on Linux it resolves the system
 * {@code libtesseract}/{@code liblept}, and a genuine load failure surfaces as an
 * {@link UnsatisfiedLinkError} — deliberately not swallowed here.
 */
public final class OcrNative {

    private OcrNative() {}

    /**
     * Traineddata bundled under {@code src/main/resources/tessdata/}. Adding a language is data-only:
     * drop its {@code <lang>.traineddata} in that folder and add the code here.
     */
    static final String[] BUNDLED_LANGUAGES = {"eng", "chi_sim", "jpn", "kor"};

    private static volatile Path tessdataDir = null;

    /**
     * Loads the OpenCV native once (idempotent), through the module-wide
     * {@link com.botmaker.shared.opencv.OpenCvNative} — this used to be its own {@code loadLocally()} call
     * with its own flag, which is one of the three independent loaders that class now replaces.
     */
    public static void ensureOpenCvLoaded() {
        com.botmaker.shared.opencv.OpenCvNative.ensureLoaded();
    }

    /**
     * The filesystem directory holding the extracted {@code *.traineddata}, suitable for
     * {@code Tesseract.setDatapath(...)}. Extracts the bundled languages to a temp dir on first call;
     * subsequent calls return the same dir.
     */
    public static synchronized Path tessdataPath() {
        if (tessdataDir != null) return tessdataDir;
        try {
            Path dir = Files.createTempDirectory("botmaker-tessdata");
            dir.toFile().deleteOnExit();
            for (String lang : BUNDLED_LANGUAGES) {
                String resource = "/tessdata/" + lang + ".traineddata";
                try (InputStream in = OcrNative.class.getResourceAsStream(resource)) {
                    if (in == null) continue; // not bundled — skip so a partial bundle still works
                    Path target = dir.resolve(lang + ".traineddata");
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().deleteOnExit();
                }
            }
            tessdataDir = dir;
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract bundled tessdata", e);
        }
    }
}
