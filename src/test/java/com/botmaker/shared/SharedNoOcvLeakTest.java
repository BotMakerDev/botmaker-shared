package com.botmaker.shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enforces the exclusion contract that {@code botmaker-session}'s pom asserts and nothing verifies:
 * <b>no class under {@code capture/} or {@code launch/} may reference an {@code org.opencv} or
 * {@code net.sourceforge.tess4j} type.</b>
 *
 * <p>session depends on shared with OpenCV and Tess4J <em>excluded</em>, so a standalone session consumer
 * does not download an OCR engine to open a nested X server. That exclusion is safe only while the packages
 * session actually reaches stay free of those types. A single import — the kind a refactor adds without
 * thinking — turns into a {@link NoClassDefFoundError} at a consumer's <em>runtime</em>, never at build time,
 * which is the worst place to find out.
 *
 * <p>The check reads compiled class files' constant pools rather than sources, so it sees the types the JVM
 * would actually try to resolve: a field type, a return type, a cast and a caught exception all land there
 * even when no {@code import} line does.
 */
class SharedNoOcvLeakTest {

    /** Packages session resolves. {@code ocr/} and {@code opencv/} are excluded by design — they are the engines. */
    private static final List<String> SESSION_VISIBLE = List.of(
            "com/botmaker/shared/capture",
            "com/botmaker/shared/launch");

    private static final List<String> BANNED = List.of("org/opencv/", "net/sourceforge/tess4j/");

    @Test
    void neitherCaptureNorLaunchReferencesTheExcludedEngines() throws IOException {
        Path classes = Paths.get("target", "classes");
        assertTrue(Files.isDirectory(classes), "run `mvn test-compile` first — " + classes.toAbsolutePath() + " is missing");

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String pkg : SESSION_VISIBLE) {
            Path root = classes.resolve(pkg);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path cls : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    scanned++;
                    for (String ref : constantPoolTypeNames(cls)) {
                        for (String banned : BANNED) {
                            if (ref.contains(banned)) {
                                violations.add(classes.relativize(cls) + " → " + ref);
                            }
                        }
                    }
                }
            }
        }

        assertTrue(scanned > 0, "scanned no classes — the package layout moved and this test stopped checking anything");
        if (!violations.isEmpty()) {
            fail("botmaker-session excludes OpenCV and Tess4J from this module, so these references "
                    + "would be NoClassDefFoundError at a session consumer's runtime:\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * Every UTF-8 constant in {@code classFile}. That is a superset of the type references — it also catches
     * method names and string literals — which is deliberate: over-reading here can only produce a false
     * failure that a human reads, never a false pass that ships.
     */
    private static List<String> constantPoolTypeNames(Path classFile) throws IOException {
        List<String> utf8 = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(classFile); DataInputStream in = new DataInputStream(raw)) {
            if (in.readInt() != 0xCAFEBABE) return utf8;
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int count = in.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8.add(in.readUTF());
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++; // longs and doubles take two constant-pool slots
                    }
                    default -> throw new IOException("unknown constant-pool tag " + tag + " in " + classFile);
                }
            }
        }
        return utf8;
    }
}
