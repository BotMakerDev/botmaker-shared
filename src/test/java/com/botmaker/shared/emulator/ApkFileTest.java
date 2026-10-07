package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An app file picked on this computer: which app it is, and the parts an install sends. */
class ApkFileTest {

    @Test
    void aPlainApkIsItselfAndNamesItsApp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("Solitaire.APK");
        Files.write(file, ApkIconTest.zip(new String[]{"AndroidManifest.xml"},
                new byte[][]{ApkLabelTest.manifestWithPackage(ApkLabelTest.literalLabel())}));

        try (ApkFile apk = ApkFile.open(file)) {
            assertEquals("com.example.solitaire", apk.packageName());
            assertEquals("Solitaire", apk.display());
            assertEquals(List.of(file), apk.apks());
            assertNull(apk.extracted());
        }
        assertTrue(Files.exists(file), "closing a plain APK removes nothing");
    }

    @Test
    void anXapkIsItsSplitsAndGameDataExtractedAndRemovedAfterwards(@TempDir Path dir) throws Exception {
        byte[] base = ApkIconTest.zip(new String[]{"AndroidManifest.xml"},
                new byte[][]{ApkLabelTest.manifestWithPackage(ApkLabelTest.literalLabel())});
        byte[] split = ApkIconTest.zip(new String[]{"resources.arsc"}, new byte[][]{new byte[8]});
        Path file = dir.resolve("game.xapk");
        Files.write(file, ApkIconTest.zip(
                new String[]{"config.arm64_v8a.apk", "com.example.solitaire.apk", "manifest.json",
                        "Android/obb/com.example.solitaire/main.1.com.example.solitaire.obb", "Android/obb/../../evil"},
                new byte[][]{split, base, "{\"package_name\":\"ignored.when.the.apk.says\"}".getBytes(StandardCharsets.UTF_8),
                        new byte[16], new byte[1]}));

        Path extracted;
        try (ApkFile apk = ApkFile.open(file)) {
            extracted = apk.extracted();
            assertEquals("com.example.solitaire", apk.packageName());
            assertEquals("Solitaire", apk.label(), "read from whichever APK names it, the base");
            assertEquals(2, apk.apks().size());
            assertEquals(List.of("/sdcard/Android/obb/com.example.solitaire/main.1.com.example.solitaire.obb"),
                    apk.obbs().stream().map(ApkFile.Obb::devicePath).toList(), "a path leaving its folder is dropped");
            assertTrue(apk.apks().stream().allMatch(p -> p.startsWith(extracted)));
        }
        assertFalse(Files.exists(extracted));
    }

    @Test
    void anArchiveThatOnlyItsManifestNamesIsReadFromThatAndAnythingElseIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("game.apks");
        Files.write(file, ApkIconTest.zip(new String[]{"base.apk", "standalones/standalone-x86.apk", "manifest.json"},
                new byte[][]{new byte[32], new byte[32],
                        "{\"name\":\"Farm\",\"package_name\":\"com.farm\"}".getBytes(StandardCharsets.UTF_8)}));
        try (ApkFile apk = ApkFile.open(file)) {
            assertEquals("com.farm", apk.packageName());
            assertEquals("Farm", apk.display());
            assertEquals(1, apk.apks().size(), "bundletool's standalone APK isn't installed beside the splits");
        }

        Path text = Files.writeString(dir.resolve("notes.txt"), "x");
        assertThrows(IOException.class, () -> ApkFile.open(text));
        Path empty = dir.resolve("empty.xapk");
        Files.write(empty, ApkIconTest.zip(new String[]{"manifest.json"}, new byte[][]{"{}".getBytes()}));
        assertThrows(IOException.class, () -> ApkFile.open(empty));
        Path notApk = dir.resolve("x.apk");
        Files.write(notApk, new byte[100]);
        assertThrows(IOException.class, () -> ApkFile.open(notApk));
    }
}
