// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
        assertTrue(library.newest("com.example.solitaire", List.of()).orElseThrow().newerThan(20));
        assertFalse(library.newest("com.example.solitaire", List.of()).orElseThrow().newerThan(30));
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
        assertThrows(IOException.class, () -> library.add(text));
        assertEquals(List.of(), library.packages());
        assertEquals(Optional.empty(), library.newest("com.example.solitaire", List.of("x86_64")));
    }

    @Test
    void fileNamesReadBackAndStrangeNamesAreSkipped() {
        assertEquals("7_1.0-beta-2.xapk", ApkLibrary.fileName(7, "1.0 beta/2", CpuAbi.ANY, "xapk"));
        assertEquals("7.apk", ApkLibrary.fileName(7, null, CpuAbi.ANY, "apk"));
        assertEquals("7_1.0-rc_x86_64.apk", ApkLibrary.fileName(7, "1.0_rc", CpuAbi.X86_64, "apk"));
        assertEquals("7__arm64-v8a.apk", ApkLibrary.fileName(7, null, CpuAbi.ARM64_V8A, "apk"));
        ApkLibrary.Entry entry = ApkLibrary.entry("a.b", Path.of("7_1.0.xapk")).orElseThrow();
        assertEquals(7, entry.versionCode());
        assertEquals("1.0", entry.versionName());
        assertEquals(CpuAbi.ANY, entry.abi(), "a name without a kind, as before 2026-11-25");
        ApkLibrary.Entry x86 = ApkLibrary.entry("a.b", Path.of("7_1.0-rc_x86_64.apk")).orElseThrow();
        assertEquals("1.0-rc", x86.versionName());
        assertEquals(CpuAbi.X86_64, x86.abi());
        ApkLibrary.Entry arm = ApkLibrary.entry("a.b", Path.of("7__arm64-v8a.apk")).orElseThrow();
        assertEquals(null, arm.versionName());
        assertEquals(CpuAbi.ARM64_V8A, arm.abi());
        assertEquals(CpuAbi.X86, ApkLibrary.entry("a.b", Path.of("7_2.0_x86.apk")).orElseThrow().abi());
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

    @Test
    void oneVersionForTwoProcessorsKeepsBothAndEachDeviceGetsItsOwn(@TempDir Path dir) throws Exception {
        ApkLibrary library = new ApkLibrary(dir.resolve("apps"));
        ApkLibrary.Entry x86 = library.add(apk(dir.resolve("x.apk"), 9, "x86_64"));
        ApkLibrary.Entry arm = library.add(apk(dir.resolve("a.apk"), 9, "arm64-v8a"));
        assertEquals(CpuAbi.X86_64, x86.abi());
        assertEquals(CpuAbi.ARM64_V8A, arm.abi());
        assertTrue(Files.exists(x86.file()) && Files.exists(arm.file()), "one version, two kinds: both kept");

        String pkg = "com.example.solitaire";
        assertEquals(x86.file(), library.newest(pkg, List.of("x86_64", "x86", "arm64-v8a")).orElseThrow().file(),
                "Waydroid with libndk lists x86_64 first");
        assertEquals(arm.file(), library.newest(pkg, List.of("arm64-v8a", "armeabi-v7a")).orElseThrow().file());
        assertTrue(library.newest(pkg, List.of("armeabi-v7a")).isEmpty(), "no file this processor runs");
    }

    @Test
    void anOldNameWithoutAKindServesEveryDeviceAndANewerOneWins(@TempDir Path dir) throws Exception {
        String pkg = "com.example.solitaire";
        Path folder = Files.createDirectories(dir.resolve("apps").resolve(pkg));
        Path old = Files.copy(apk(dir.resolve("o.apk"), 8), folder.resolve("8_1.2.3.apk"));
        ApkLibrary library = new ApkLibrary(dir.resolve("apps"));
        assertEquals(old, library.newest(pkg, List.of("x86_64")).orElseThrow().file());
        assertEquals(old, library.newest(pkg, List.of("arm64-v8a")).orElseThrow().file());

        ApkLibrary.Entry same = library.add(apk(dir.resolve("s.apk"), 8, "x86_64"));
        assertEquals(same.file(), library.newest(pkg, List.of("x86_64")).orElseThrow().file(),
                "of one version, the device's own kind wins over any");
        assertEquals(old, library.newest(pkg, List.of("arm64-v8a")).orElseThrow().file());
    }

    @Test
    void aPruneKeepsTwoVersionsOfEachKind(@TempDir Path dir) throws Exception {
        ApkLibrary library = new ApkLibrary(dir.resolve("apps"));
        for (int code : new int[]{1, 2, 3}) {
            library.add(apk(dir.resolve("x" + code + ".apk"), code, "x86_64"));
            library.add(apk(dir.resolve("a" + code + ".apk"), code, "arm64-v8a"));
        }
        List<ApkLibrary.Entry> versions = library.versions("com.example.solitaire");
        assertEquals(List.of(3L, 2L), versions.stream().filter(e -> e.abi() == CpuAbi.X86_64)
                .map(ApkLibrary.Entry::versionCode).toList());
        assertEquals(List.of(3L, 2L), versions.stream().filter(e -> e.abi() == CpuAbi.ARM64_V8A)
                .map(ApkLibrary.Entry::versionCode).toList());
        assertTrue(library.size() > 0);
    }

    @Test
    void aFileBeingInstalledIsNeitherDeletedNorPruned(@TempDir Path dir) throws Exception {
        ApkLibrary library = new ApkLibrary(dir.resolve("apps"));
        ApkLibrary.Entry first = library.add(apk(dir.resolve("1.apk"), 1));
        try (ApkLibrary.Lease reading = ApkLibrary.installing(first.file())) {
            IOException refused = assertThrows(IOException.class, () -> library.delete(first));
            assertTrue(refused.getMessage().contains("being installed"), refused.getMessage());
            library.add(apk(dir.resolve("2.apk"), 2));
            library.add(apk(dir.resolve("3.apk"), 3));
            assertTrue(Files.exists(first.file()), "a prune leaves the file an install reads");
        }
        assertFalse(ApkLibrary.inUse(first.file()));
        assertTrue(library.delete(first));
        assertFalse(library.delete(first), "gone already");
    }

    @Test
    void theKindIsTheOneUnderLibAndSeveralKindsAreAny(@TempDir Path dir) throws Exception {
        assertEquals(CpuAbi.X86_64, ApkLibrary.abiOf(List.of(apk(dir.resolve("a.apk"), 1, "x86_64"))));
        assertEquals(CpuAbi.ANY, ApkLibrary.abiOf(List.of(apk(dir.resolve("b.apk"), 1))), "no native code");
        assertEquals(CpuAbi.ANY, ApkLibrary.abiOf(List.of(apk(dir.resolve("c.apk"), 1, "x86_64"),
                apk(dir.resolve("d.apk"), 1, "arm64-v8a"))), "a fat app runs on both");
        assertEquals(CpuAbi.ANY, ApkLibrary.abiOf(List.of(apk(dir.resolve("e.apk"), 1, "riscv64"))));
        assertEquals(CpuAbi.UNKNOWN, CpuAbi.fromId("riscv64"));
        assertEquals(CpuAbi.ANY, CpuAbi.fromId(null));
        assertEquals(CpuAbi.ARM64_V8A, CpuAbi.fromId(" ARM64-v8a "));
    }

    /** {@link #apk(Path, int)} with native code for the processor {@code abi}. */
    static Path apk(Path file, int code, String abi) throws Exception {
        return Files.write(file, ApkIconTest.zip(new String[]{"AndroidManifest.xml", "lib/" + abi + "/libgame.so"},
                new byte[][]{ApkLabelTest.manifestWithVersion(code), new byte[]{1}}));
    }

    /** A plain APK of {@code com.example.solitaire} at {@code versionCode} {@code code}, version name 1.2.3. */
    static Path apk(Path file, int code) throws Exception {
        return Files.write(file, ApkIconTest.zip(new String[]{"AndroidManifest.xml"},
                new byte[][]{ApkLabelTest.manifestWithVersion(code)}));
    }
}
