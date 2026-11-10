package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** goopdl's command line, the processors tried, the packed files and the ABI list; no network. */
class PlayDownloadTest {

    @Test
    void goopdlAsksForSplitsAndExtrasForOneProcessor() {
        assertEquals(List.of("/v/goopdl", "download", "com.foo.game", "-o", "/out", "-a", "arm64", "--splits",
                "--extras"), PlayDownload.command(Path.of("/v/goopdl"), "com.foo.game", "arm64", Path.of("/out")));
    }

    @Test
    void waydroidTriesItsOwnProcessorsThenTheArmOnesItTranslates() {
        assertEquals(List.of("x86_64", "x86", "arm64", "armv7"),
                PlayDownload.archs(List.of("x86_64", "x86", "arm64-v8a", "armeabi-v7a", "armeabi")));
        assertEquals(List.of("arm64"), PlayDownload.archs(List.of()), "unknown: goopdl's default");
        assertEquals(List.of("arm64"), PlayDownload.archs(List.of("riscv64")));
    }

    @Test
    void anErrorIsItsWordsWithoutTheBoxAroundIt() {
        assertEquals("Missing option '--version' / '-v'.",
                PlayDownload.words("│ Missing option '--version' / '-v'.                    │"));
        assertEquals("", PlayDownload.words("╰──────────────────╯"));
        assertEquals("Google Play does not serve version 1 of x.", PlayDownload.words(
                "Google Play does not serve version 1 of x."));
    }

    @Test
    void playsNewestVersionIsReadFromGoopdlsInfoRow() {
        assertEquals(53000000L, PlayDownload.versionCode(PlayDownload.words(
                "│ Version    │ 5.300.0 (53000000)                    │")).orElseThrow());
        assertFalse(PlayDownload.versionCode("Developer DuckDuckGo").isPresent());
        assertFalse(PlayDownload.versionCode("Version (0)").isPresent(), "Play serves nothing to this processor");
    }

    @Test
    void goopdlStaysAnonymousWhateverTheEnvironmentHolds() {
        assertEquals("", PlayDownload.ENVIRONMENT.get("GOOPDL_ACCOUNT_EMAIL"));
        assertEquals("", PlayDownload.ENVIRONMENT.get("GOOPDL_AAS_TOKEN"));
    }

    @Test
    void theSignedInAccountIsGoopdlsDirectAccountOnTheSecondTry() {
        var env = PlayDownload.environment(new GoogleAccount.Account("bot@example.com", "aas_et/abc"));
        assertEquals("bot@example.com", env.get("GOOPDL_ACCOUNT_EMAIL"));
        assertEquals("aas_et/abc", env.get("GOOPDL_AAS_TOKEN"));
        assertEquals(PlayDownload.ENVIRONMENT.get("COLUMNS"), env.get("COLUMNS"));
    }

    @Test
    void theFilesArePackedAsOneXapkWithTheObbsUnderAndroidObb(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("com.foo.game-7.apk"), new byte[]{1});
        Files.write(dir.resolve("com.foo.game-7-UnityDataAssetPack.apk"), new byte[]{2});
        Files.write(dir.resolve("main.7.com.foo.game.obb"), new byte[]{3});
        Files.write(dir.resolve("com.foo.game-7.dm"), new byte[]{4});
        Path xapk = dir.resolve("com.foo.game.xapk");
        PlayDownload.pack(dir, "com.foo.game", xapk);
        try (ZipFile zip = new ZipFile(xapk.toFile())) {
            assertEquals(List.of("com.foo.game-7-UnityDataAssetPack.apk", "com.foo.game-7.apk",
                            "Android/obb/com.foo.game/main.7.com.foo.game.obb"),
                    zip.stream().map(ZipEntry::getName).toList());
        }
    }

    @Test
    void aDownloadWithNoApkIsRefused(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("main.7.com.foo.game.obb"), new byte[]{3});
        assertThrows(IOException.class, () -> PlayDownload.pack(dir, "com.foo.game", dir.resolve("x.xapk")));
    }

    @Test
    void theDevicesProcessorsAreItsAbiListElseItsOneAbi() {
        assertEquals(List.of("x86_64", "x86", "arm64-v8a"), EmulatorInstall.abis("x86_64,x86,arm64-v8a\n", "x86_64"));
        assertEquals(List.of("arm64-v8a"), EmulatorInstall.abis("", "arm64-v8a\n"));
        assertEquals(List.of(), EmulatorInstall.abis(null, null));
    }

    @Test
    void anInvalidPackageIsRefusedBeforeAnyDownload(@TempDir Path dir) {
        assertFalse(PlayDownload.fetch("not a package", List.of(), new ApkLibrary(dir), null).ok());
    }
}
