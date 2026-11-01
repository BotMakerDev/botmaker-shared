package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** apkeep's command line, the store pages, and what a download left behind; no network. */
class ApkDownloadTest {

    @Test
    void apkeepAsksApkPureForTheDevicesProcessorWithTheDangersAcknowledged() {
        assertEquals(List.of("/t/apkeep", "-a", "com.foo.game", "-d", "apk-pure", "-o",
                        "acknowledge_dangers=true,arch=x86_64", "/out"),
                ApkDownload.command(Path.of("/t/apkeep"), "com.foo.game", "x86_64\n", Path.of("/out")));
        assertEquals("acknowledge_dangers=true",
                ApkDownload.command(Path.of("/t/apkeep"), "com.foo.game", "", Path.of("/out")).get(6));
        assertEquals("acknowledge_dangers=true",
                ApkDownload.command(Path.of("/t/apkeep"), "com.foo.game", "x86,split_apk=1", Path.of("/out")).get(6),
                "a comma is no ABI and adds no option");
    }

    @Test
    void withoutTheUsersOkNothingIsDownloaded(@TempDir Path dir) {
        ApkDownload.Result result = ApkDownload.fetch("com.foo.game", "x86_64", false, new ApkLibrary(dir), null);
        assertFalse(result.ok());
        assertTrue(result.message().contains(ApkDownload.DANGERS), result.message());
        assertFalse(ApkDownload.fetch("not a package", "", true, new ApkLibrary(dir), null).ok());
    }

    @Test
    void theUsersOkIsKeptUntilWithdrawn(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        assertFalse(ApkDownload.acknowledged(config));
        ApkDownload.acknowledge(config, true);
        assertTrue(ApkDownload.acknowledged(config));
        ApkDownload.acknowledge(config, false);
        assertFalse(ApkDownload.acknowledged(config));
        ApkDownload.acknowledge(config, false);
    }

    @Test
    void googlePlayIsStepsTheUserTakesPerEmulator() {
        PlayStoreSetup waydroid = PlayStoreSetup.of(PlatformId.WAYDROID, "Waydroid");
        assertEquals("sudo waydroid init -s GAPPS -f", waydroid.steps().getFirst().command());
        assertTrue(waydroid.text().contains(PlayStoreSetup.REGISTER), waydroid.text());
        assertTrue(PlayStoreSetup.of(PlatformId.BLUESTACKS, "Pie64").text().contains("Pie64"));
        assertTrue(PlayStoreSetup.of(PlatformId.PHYSICAL, "Pixel").steps().isEmpty());
    }

    @Test
    void thePagesSearchApkPureThenApkMirrorForThePackage() {
        assertEquals(List.of("https://apkpure.com/search?q=com.foo.game",
                        "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=com.foo.game"),
                ApkDownload.pages("com.foo.game"));
    }

    @Test
    void theDownloadedFileIsTheAppApkeepLeft(@TempDir Path dir) throws Exception {
        assertEquals(Optional.empty(), ApkDownload.downloadedFile(dir));
        Files.writeString(dir.resolve("log.txt"), "x");
        Path xapk = Files.write(dir.resolve("com.foo.game@x86_64.xapk"), new byte[16]);
        assertEquals(Optional.of(xapk), ApkDownload.downloadedFile(dir));
    }
}
