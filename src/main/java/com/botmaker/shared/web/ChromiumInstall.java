// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.Unzip;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * <b>BotMaker's own Chromium</b>: Chrome for Testing at one pinned version, pinned as {@code ManagedTools} pins adb and
 * scrcpy — a URL, a sha256 and a byte count per platform — downloaded into {@code UserDirs.cache()/chromium/<version>/}
 * and unzipped there. A digest that does not match installs nothing. Never the user's browser.
 *
 * <p>The pins are constants read once from Google's {@code last-known-good-versions-with-downloads.json} (Stable,
 * 2026-11-18) and the zips' own bytes, which that file does not hash; nothing is fetched to decide what to install.
 */
public final class ChromiumInstall {

    private ChromiumInstall() {}

    /** The Chrome for Testing version pinned below. */
    public static final String VERSION = "158.0.8127.36";

    /** The platforms Chrome for Testing publishes that BotMaker runs on, by the name its URLs use. */
    public enum Platform {
        WIN64("win64", "6a8ad290952cc612c7777ffb3eb62f345539a2a1a8d440cfac190c6d36b34d2c", 214_927_656L),
        LINUX64("linux64", "dba9cc838e1ce269c33ea68170881127bd15d078fc3ca5891c90802819a4d7c7", 208_062_943L),
        MAC_ARM64("mac-arm64", "b86d3b393def1f87d369b022abff0a51acb015d6fcb79ddfd1c379c9d72fbbe2", 200_339_070L),
        MAC_X64("mac-x64", "1390e9b1386a1fd8ec2c4940b9ff59cbbda71b896b85182d7efbd73c58213802", 213_549_680L);

        private final String id;
        private final String sha256;
        private final long size;

        Platform(String id, String sha256, long size) {
            this.id = id;
            this.sha256 = sha256;
            this.size = size;
        }

        public String id() {
            return id;
        }

        /** The pinned zip. */
        public Downloads.Remote remote() {
            return new Downloads.Remote("https://storage.googleapis.com/chrome-for-testing-public/" + VERSION + "/"
                    + id + "/chrome-" + id + ".zip", Downloads.Digest.SHA_256, sha256, size);
        }

        /** The executable inside the unpacked zip, relative to {@link #directory()}. */
        public Path binary() {
            Path root = Path.of("chrome-" + id);
            return switch (this) {
                case WIN64 -> root.resolve("chrome.exe");
                case LINUX64 -> root.resolve("chrome");
                case MAC_ARM64, MAC_X64 -> root.resolve("Google Chrome for Testing.app").resolve("Contents")
                        .resolve("MacOS").resolve("Google Chrome for Testing");
            };
        }

        /** The platform of an OS and processor, by their system properties' values; empty for one not published. */
        public static Optional<Platform> of(String osName, String arch) {
            String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            String cpu = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
            boolean x64 = cpu.equals("amd64") || cpu.equals("x86_64");
            boolean arm64 = cpu.equals("aarch64") || cpu.equals("arm64");
            if (os.contains("win") && x64) {
                return Optional.of(WIN64);
            }
            if (os.contains("mac") && arm64) {
                return Optional.of(MAC_ARM64);
            }
            if (os.contains("mac") && x64) {
                return Optional.of(MAC_X64);
            }
            if (os.contains("nux") && x64) {
                return Optional.of(LINUX64);
            }
            return Optional.empty();
        }

        /** This computer's platform. */
        public static Optional<Platform> current() {
            return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        }
    }

    /** Where the pinned version unpacks, whether or not it is there. */
    public static Path directory() {
        return UserDirs.cache().resolve("chromium").resolve(VERSION);
    }

    /** The pinned Chromium's executable on this computer, if it is installed. */
    public static Optional<Path> installed() {
        return Platform.current().map(p -> directory().resolve(p.binary())).filter(Files::isExecutable);
    }

    /**
     * Downloads and unpacks the pinned Chromium when it is not installed; returns its executable, or empty on a
     * platform with no build or when the download or unpacking failed. About 200 MB, once per version.
     */
    public static Optional<Path> install(Downloads.Progress progress) {
        Optional<Path> installed = installed();
        if (installed.isPresent()) {
            return installed;
        }
        Optional<Platform> platform = Platform.current();
        if (platform.isEmpty()) {
            return Optional.empty();
        }
        // Per process, so two installs at once share no file; the tree is unpacked beside its place and moved there
        // whole, so a half-unpacked one never counts as installed.
        String mine = VERSION + "-" + platform.get().id() + "-" + ProcessHandle.current().pid();
        Path archive = directory().resolveSibling(mine + ".zip");
        Path unpacking = directory().resolveSibling(mine + ".unpacking");
        try {
            if (!Downloads.fetch(platform.get().remote(), archive, progress)) {
                return Optional.empty();
            }
            boolean unpacked = platform.get() == Platform.MAC_ARM64 || platform.get() == Platform.MAC_X64
                    ? ditto(archive, unpacking)
                    : Unzip.extract(archive, unpacking);
            if (!unpacked || !Files.isExecutable(unpacking.resolve(platform.get().binary()))) {
                return Optional.empty();
            }
            Files.move(unpacking, directory(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Another install moved its tree in first, or the move failed: what is in place decides.
            return installed();
        } finally {
            Chromium.deleteQuietly(unpacking);
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // A leftover zip costs disk, not correctness: the unpacked tree is what anything starts.
            }
        }
        return installed();
    }

    /**
     * Unpacks with macOS's own {@code ditto}: the app bundle's frameworks are symbolic links, which a
     * {@code ZipInputStream} writes as small files and so breaks the app.
     */
    private static boolean ditto(Path archive, Path target) {
        try {
            Files.createDirectories(target);
            Process process = new ProcessBuilder("/usr/bin/ditto", "-x", "-k", archive.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
