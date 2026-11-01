package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-game APK library: files named by version, newest first, two kept, a download found in Downloads. */
class ApkLibraryTest {

    @Test
    void anAddedFileIsFiledUnderItsPackageAndVersionAndOnlyTheTwoNewestStay(@TempDir Path dir) throws Exception {
        ApkLibrary library = new ApkLibrary(dir.resolve("apps"));
        for (int code : new int[]{10, 30, 20}) {
            library.add(apk(dir.resolve("in-" + code + ".apk"), code));
        }

        assertEquals(List.of("com.example.solitaire"), library.packages());
        List<ApkLibrary.Entry> versions = library.versions("com.example.solitaire");
        assertEquals(List.of(30L, 20L), versions.stream().map(ApkLibrary.Entry::versionCode).toList(),
                "newest first, the oldest of three pruned");
        assertEquals(dir.resolve("apps/com.example.solitaire/30_1.2.3.apk"), versions.getFirst().file());
        assertEquals("1.2.3", versions.getFirst().version());
        assertTrue(library.newest("com.example.solitaire").orElseThrow().newerThan(20));
        assertFalse(library.newest("com.example.solitaire").orElseThrow().newerThan(30));
    }

    @Test
    void anOlderFileJustAddedStaysToBeInstalledAndOneVersionInTwoFilesCountsOnce(@TempDir Path dir) throws Exception {
        ApkLibrary library = new ApkLibrary(dir.resolve("apps"));
        library.add(apk(dir.resolve("a.apk"), 5));
        library.add(apk(dir.resolve("b.apk"), 6));
        ApkLibrary.Entry older = library.add(apk(dir.resolve("c.apk"), 4));
        assertTrue(Files.exists(older.file()), "the file just added is the one to install");

        Path xapk = dir.resolve("d.xapk");
        Files.write(xapk, ApkIconTest.zip(new String[]{"base.apk"},
                new byte[][]{Files.readAllBytes(apk(dir.resolve("e.apk"), 6))}));
        library.add(xapk);
        assertEquals(List.of(6L, 6L, 5L), library.versions("com.example.solitaire").stream()
                .map(ApkLibrary.Entry::versionCode).toList(), "two files of 6 are one version: 5 stays");
        assertTrue(ApkLibrary.entry("a.b", Path.of("99999999999999999999.apk")).isEmpty(), "no version fits");
    }

    @Test
    void aFileThatIsNoAppIsRefusedAndAnEmptyLibraryHasNothing(@TempDir Path dir) throws Exception {
        ApkLibrary library = new ApkLibrary(dir.resolve("apps"));
        Path text = Files.writeString(dir.resolve("notes.txt"), "hi");
        assertThrows(java.io.IOException.class, () -> library.add(text));
        assertEquals(List.of(), library.packages());
        assertEquals(Optional.empty(), library.newest("com.example.solitaire"));
    }

    @Test
    void fileNamesReadBackAndStrangeNamesAreSkipped() {
        assertEquals("7_1.0-beta-2.xapk", ApkLibrary.fileName(7, "1.0 beta/2", "xapk"));
        assertEquals("7.apk", ApkLibrary.fileName(7, null, "apk"));
        ApkLibrary.Entry entry = ApkLibrary.entry("a.b", Path.of("7_1.0.xapk")).orElseThrow();
        assertEquals(7, entry.versionCode());
        assertEquals("1.0", entry.versionName());
        assertEquals("7", ApkLibrary.entry("a.b", Path.of("7.apk")).orElseThrow().version());
        assertTrue(ApkLibrary.entry("a.b", Path.of("7.apk.part")).isEmpty());
        assertTrue(ApkLibrary.entry("a.b", Path.of("readme.txt")).isEmpty());
    }

    @Test
    void aDownloadIsTheNewestFileOfThatPackageChangedSinceThePageOpened(@TempDir Path dir) throws Exception {
        Instant opened = Instant.now().minusSeconds(60);
        Path old = apk(dir.resolve("old.apk"), 1);
        Files.setLastModifiedTime(old, FileTime.from(opened.minusSeconds(60)));
        assertTrue(ApkLibrary.downloaded(dir, "com.example.solitaire", opened).isEmpty(), "older than the page");

        Path fresh = apk(dir.resolve("fresh.apk"), 2);
        Files.writeString(dir.resolve("half.apk.crdownload"), "…");
        assertEquals(Optional.of(fresh), ApkLibrary.downloaded(dir, "com.example.solitaire", opened));
        assertTrue(ApkLibrary.downloaded(dir, "com.other.game", opened).isEmpty(), "another package");
        assertTrue(ApkLibrary.downloaded(dir.resolve("missing"), "com.example.solitaire", opened).isEmpty());
    }

    /** A plain APK of {@code com.example.solitaire} at {@code versionCode} {@code code}, version name 1.2.3. */
    static Path apk(Path file, int code) throws Exception {
        return Files.write(file, ApkIconTest.zip(new String[]{"AndroidManifest.xml"},
                new byte[][]{ApkLabelTest.manifestWithVersion(code)}));
    }
}
