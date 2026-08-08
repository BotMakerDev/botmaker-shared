package com.botmaker.shared.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extraction: the traversal cases first, because an archive that can write outside its target directory is the
 * one failure here that is not merely a missing tool.
 */
class UnzipTest {

    private static Path zipOf(Path dir, String name, Map<String, String> entries) throws Exception {
        Path zip = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void anEntryThatWouldEscapeTheTargetIsRejected(@TempDir Path dir) {
        Path root = dir.resolve("target");
        assertNull(Unzip.resolve(root, "../escaped"));
        assertNull(Unzip.resolve(root, "a/../../escaped"));
        assertNull(Unzip.resolve(root, "/etc/passwd"));
        // A backslash is an ordinary filename character on Linux, so this one only escapes on the machine that
        // wrote the archive — which is exactly why it is normalised before the check rather than after.
        assertNull(Unzip.resolve(root, "..\\escaped"));
        assertNull(Unzip.resolve(root, ""));
        assertNotNull(Unzip.resolve(root, "platform-tools/adb"));
    }

    @Test
    void oneBadEntryFailsTheWholeExtraction(@TempDir Path dir) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("platform-tools/adb", "binary");
        entries.put("../escaped", "no");
        Path target = dir.resolve("out");
        assertFalse(Unzip.extract(zipOf(dir, "evil.zip", entries), target));
        assertFalse(Files.exists(dir.resolve("escaped")));
    }

    @Test
    void anOrdinaryArchiveKeepsItsOwnRoot(@TempDir Path dir) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("platform-tools/adb", "binary");
        entries.put("platform-tools/adb.exe", "windows");
        entries.put("platform-tools/api/api.txt", "text");
        Path target = dir.resolve("tools");
        assertTrue(Unzip.extract(zipOf(dir, "platform-tools.zip", entries), target));
        assertTrue(Files.isRegularFile(target.resolve("platform-tools/adb")));
        assertTrue(Files.isRegularFile(target.resolve("platform-tools/api/api.txt")));
    }

    /**
     * A {@code ZipEntry} carries no POSIX mode, so without this an extracted {@code adb} is present and not
     * runnable — which every later probe reports as "no adb found".
     */
    @Test
    void theExtensionlessFilesComeOutExecutable(@TempDir Path dir) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("platform-tools/adb", "binary");
        entries.put("platform-tools/adb.exe", "windows");
        Path target = dir.resolve("tools");
        assertTrue(Unzip.extract(zipOf(dir, "platform-tools.zip", entries), target));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertTrue(Files.isExecutable(target.resolve("platform-tools/adb")));
            assertFalse(Files.isExecutable(target.resolve("platform-tools/adb.exe")));
        }
        assertTrue(Unzip.executable(Path.of("platform-tools/fastboot")));
        assertFalse(Unzip.executable(Path.of("platform-tools/libwinpthread-1.dll")));
    }
}
