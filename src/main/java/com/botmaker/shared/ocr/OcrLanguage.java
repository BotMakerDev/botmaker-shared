package com.botmaker.shared.ocr;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The traineddata bundled under {@code src/main/resources/tessdata/} — the closed set of languages
 * {@link OcrEngine} can actually recognise without the user installing anything.
 *
 * <p>It is an enum rather than four string literals because the set had two owners that could drift: the
 * extractor listed {@code {"eng","chi_sim","jpn","kor"}} and {@link OcrOptions#defaults()} separately spelled
 * {@code "eng"}, so a language whose file was dropped from the bundle still read as available at the default.
 * Adding a language stays data-only in spirit — drop its {@code <lang>.traineddata} in that folder and add a
 * constant here; the extractor picks it up from {@link #values()}.
 *
 * <p>The {@link #code()} is Tesseract's own file/language name and is what crosses into the engine, so it
 * must keep matching the bundled file name exactly. What does <em>not</em> become a type is
 * {@link OcrOptions#languages()} itself: that is a {@code +}-joined multi-language spec Tesseract parses, and
 * a bot may legitimately name a language it installed system-wide. Build the common cases with
 * {@link #spec(OcrLanguage...)}.
 */
public enum OcrLanguage {

    ENGLISH("eng", "English"),
    SIMPLIFIED_CHINESE("chi_sim", "Chinese (Simplified)"),
    JAPANESE("jpn", "Japanese"),
    KOREAN("kor", "Korean");

    /** What Tesseract joins several languages with in one {@code setLanguage} spec. */
    private static final String SEPARATOR = "+";

    private final String code;
    private final String displayName;

    OcrLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** Tesseract's language code — also the bundled {@code <code>.traineddata} file name. */
    public String code() {
        return code;
    }

    /** Human-readable name, for a language picker in Studio. */
    public String displayName() {
        return displayName;
    }

    /** The bundled file name this language is extracted from. */
    public String trainedDataFile() {
        return code + ".traineddata";
    }

    /** The {@code eng+chi_sim} spec for {@code languages}, ready for {@link OcrOptions#withLanguages}. */
    public static String spec(OcrLanguage... languages) {
        return Arrays.stream(languages).map(OcrLanguage::code).collect(Collectors.joining(SEPARATOR));
    }

    /** The language with this Tesseract code, or {@code null} — a spec may name one we don't bundle. */
    public static OcrLanguage fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        for (OcrLanguage language : values()) {
            if (language.code.equalsIgnoreCase(trimmed)) {
                return language;
            }
        }
        return null;
    }
}
