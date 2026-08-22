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
 * call, and <b>no code here participates</b>: the build stages the natives into the jar under JNA's
 * {@code Platform.RESOURCE_PREFIX} ({@code win32-x86-64/} from Tess4J itself, {@code linux-x86-64/} from
 * {@code pom.xml}), and Tess4J's own {@code LoadLibs} extracts that prefix and prepends it to
 * {@code jna.library.path}. So OCR is self-contained on Windows <em>and</em> Linux — including in a
 * generated bot and in the AppImage, neither of which can declare a package dependency. A genuine load
 * failure still surfaces as an {@link UnsatisfiedLinkError} — deliberately not swallowed here.
 *
 * <p>The Linux natives' versions are coupled to Tess4J's, and {@code OcrEngineNativeTest} is what keeps
 * them honest; see the note above the pins in {@code pom.xml} before bumping any of the three.
 */
public final class OcrNative {

    private OcrNative() {}

    /** Classpath folder the traineddata are bundled in — the resource side of {@link OcrLanguage}. */
    private static final String TESSDATA_RESOURCE_DIR = "/tessdata/";

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
            for (OcrLanguage lang : OcrLanguage.values()) {
                String resource = TESSDATA_RESOURCE_DIR + lang.trainedDataFile();
                try (InputStream in = OcrNative.class.getResourceAsStream(resource)) {
                    if (in == null) continue; // not bundled — skip so a partial bundle still works
                    Path target = dir.resolve(lang.trainedDataFile());
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
