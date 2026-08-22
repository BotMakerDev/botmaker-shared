package com.botmaker.shared.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the OCR stack's <b>native</b> contract, which the compiler cannot see and which fails, when it
 * fails, inside a user's bot rather than in CI.
 *
 * <p>Two independent things are asserted:
 *
 * <ul>
 *   <li><b>The Tesseract/Leptonica/Tess4J versions still agree.</b> Tess4J pins a lept4j whose generated
 *       bindings target one exact Leptonica release, and {@code pom.xml} pins the JavaCPP natives to match.
 *       A bump of one without the others throws an undefined-symbol {@link UnsatisfiedLinkError} out of
 *       {@code Leptonica1}'s static initialiser — but only on the {@code getWords} path, which is why this
 *       test calls {@link OcrEngine#recognize} and not just {@link OcrEngine#text}.</li>
 *   <li><b>The bundled natives are the ones actually loaded</b> (Linux only). A developer machine with
 *       {@code tesseract-libs} installed will happily OCR through the <em>system</em> library, so a green
 *       recognition result on its own proves nothing about what we ship. This reads {@code /proc/self/maps}
 *       and fails if a system path is mapped.</li>
 * </ul>
 */
class OcrEngineNativeTest {

    /** An image Tesseract should read confidently at default options, without a font dependency worth tuning. */
    private static BufferedImage textImage(String text) {
        BufferedImage img = new BufferedImage(460, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 48));
        g.drawString(text, 20, 62);
        g.dispose();
        return img;
    }

    @Test
    void recognizesWordsThroughTheLeptonicaBindings() {
        // recognize() -> Tess4J getWords() -> lept4j convertImageToPix(): the version-coupled path.
        List<TextResult> words = OcrEngine.recognize(textImage("BotMaker 42"), OcrOptions.defaults());

        String joined = words.stream().map(TextResult::text).collect(Collectors.joining(" "));
        assertEquals("BotMaker 42", joined,
                "OCR of a clean rendered string should round-trip exactly; got " + words);
        assertTrue(words.stream().allMatch(w -> w.confidence() > 50f),
                "every word should be recognised confidently; got " + words);
    }

    /**
     * The natives we ship must win over any the host happens to have installed. They are staged into the
     * jar at {@code linux-x86-64/} (JNA's {@code Platform.RESOURCE_PREFIX}); Tess4J and lept4j extract that
     * prefix to their own temp dirs and prepend those to {@code jna.library.path}.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void loadsTheBundledNativesAndNotTheSystemOnes() throws IOException {
        OcrEngine.recognize(textImage("proof"), OcrOptions.defaults()); // force the load

        Set<String> mapped;
        try (Stream<String> lines = Files.lines(Path.of("/proc/self/maps"))) {
            mapped = lines.filter(l -> l.contains("tesseract") || l.contains("lepton"))
                    .map(l -> l.substring(l.lastIndexOf(' ') + 1))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }

        assertFalse(mapped.isEmpty(), "no Tesseract/Leptonica library was mapped at all");
        for (String path : mapped) {
            assertFalse(path.startsWith("/usr/") || path.startsWith("/lib"),
                    "a system OCR native was loaded instead of the bundled one: " + path
                            + " (all mapped: " + mapped + ")");
        }
    }
}
