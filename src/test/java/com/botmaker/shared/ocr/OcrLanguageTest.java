package com.botmaker.shared.ocr;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The bundled-language set: every constant is actually on the classpath, and the spec round-trips. */
class OcrLanguageTest {

    @Test
    void everyDeclaredLanguageIsActuallyBundled() {
        // The point of the enum: a constant with no traineddata behind it was previously invisible until a
        // bot asked for it and Tesseract returned nothing.
        for (OcrLanguage language : OcrLanguage.values()) {
            try (InputStream in = OcrNative.class.getResourceAsStream("/tessdata/" + language.trainedDataFile())) {
                assertNotNull(in, language + " declares " + language.trainedDataFile() + " but it isn't bundled");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void theDefaultOptionsNameABundledLanguage() {
        assertSame(OcrLanguage.ENGLISH, OcrLanguage.fromCode(OcrOptions.defaults().languages()));
    }

    @Test
    void severalLanguagesJoinTheWayTesseractReadsThem() {
        assertEquals("eng", OcrLanguage.spec(OcrLanguage.ENGLISH));
        assertEquals("eng+chi_sim", OcrLanguage.spec(OcrLanguage.ENGLISH, OcrLanguage.SIMPLIFIED_CHINESE));
        assertEquals("jpn+kor",
                OcrOptions.defaults().withLanguages(OcrLanguage.JAPANESE, OcrLanguage.KOREAN).languages());
    }

    @Test
    void theParseIsTotalBecauseASpecMayNameASystemInstalledLanguage() {
        assertSame(OcrLanguage.KOREAN, OcrLanguage.fromCode(" KOR "));
        assertNull(OcrLanguage.fromCode("deu"));
        assertNull(OcrLanguage.fromCode("  "));
        assertNull(OcrLanguage.fromCode(null));
    }
}
