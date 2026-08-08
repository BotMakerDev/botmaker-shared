package com.botmaker.shared.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * <b>The two host tools BotMaker can install for itself</b> — Google's {@code adb} and Genymobile's
 * {@code scrcpy-server} — pinned to an exact version and an exact digest, downloaded into
 * {@link UserDirs#cache()}, and found there afterwards by {@code AdbTools} and {@code ScrcpyServer}.
 *
 * <p>Both were previously the end of a sentence telling the user to go and install something, which is a dead
 * end inside an application whose premise is that a phone should just work. Neither actually needs a package
 * manager:
 *
 * <ul>
 *   <li><b>{@code adb}</b> is the only way to reach a phone over a <b>USB cable</b> (adbd speaks the ADB
 *       protocol over a USB endpoint, which no TCP client can dial) or over <b>Android 11+ wireless
 *       debugging</b> (TLS-wrapped; dadb implements no STLS). It is not a preference — those two routes exist
 *       nowhere else. See {@code AdbTools} for the long form.</li>
 *   <li><b>{@code scrcpy-server}</b> is a single ~0.7 MB file, and it is <em>all</em> the fast path needs:
 *       {@code ScrcpyChannel} speaks the protocol itself and only ever pushes this dex to the device. The
 *       scrcpy client is never run. "Install scrcpy" was asking for an entire application in order to use one
 *       file out of it.</li>
 * </ul>
 *
 * <h2>Pinned, not "latest"</h2>
 *
 * <p>Each pin carries a URL, a digest and a byte count, so a fetched file is either exactly the one this code
 * was written against or it is not installed at all. That is worth more here than in most downloads because
 * both of these are then <em>executed</em> — {@code adb} on this machine, {@code scrcpy-server} on the user's
 * phone. It also keeps the scrcpy wire format a known quantity: {@link #SCRCPY_VERSION} is the version whose
 * {@code Options} parser and socket header were read alongside {@code ScrcpyChannel}, rather than whatever
 * happens to be newest on the day a user clicks.
 *
 * <p>When a pin ages the failure is loud — a 404, or a digest that does not match — never a silent downgrade.
 * Bumping one means: change the version, the URL and the digest together, and re-check
 * {@code ScrcpyChannel.arguments} against that release's option parser.
 */
public final class ManagedTools {

    private ManagedTools() {}

    /** The scrcpy release this stack is written against. Also the version string handed to the server. */
    public static final String SCRCPY_VERSION = "4.1";

    /** The platform-tools revision pinned below, for anything that wants to show it. */
    public static final String PLATFORM_TOOLS_REVISION = "37.0.1";

    /**
     * The scrcpy server dex: one file, digest from Genymobile's own {@code SHA256SUMS.txt} for the release.
     *
     * <p>The file name matters and is kept: {@code ScrcpyServer.findVersion} falls back to reading the version
     * out of the file name, so {@code scrcpy-server-v4.1} needs no separate record of what version it is.
     */
    public static final Downloads.Remote SCRCPY_SERVER = new Downloads.Remote(
            "https://github.com/Genymobile/scrcpy/releases/download/v" + SCRCPY_VERSION
                    + "/scrcpy-server-v" + SCRCPY_VERSION,
            Downloads.Digest.SHA_256,
            "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae",
            733_706);

    /**
     * Where BotMaker's own copies live — under the cache, because both are re-downloadable by definition and a
     * user reclaiming disk space should be able to delete them without losing anything they typed.
     */
    public static Path directory() {
        return UserDirs.cache().resolve("tools");
    }

    /** The pinned {@code scrcpy-server} file, whether or not it has been downloaded yet. */
    public static Path scrcpyServerPath() {
        return directory().resolve("scrcpy").resolve("scrcpy-server-v" + SCRCPY_VERSION);
    }

    /** The pinned {@code scrcpy-server}, if it is installed. */
    public static Optional<File> scrcpyServer() {
        Path path = scrcpyServerPath();
        return Files.isRegularFile(path) ? Optional.of(path.toFile()) : Optional.empty();
    }

    /** BotMaker's own {@code adb}, if it has been installed. Never the user's — that lookup is AdbTools'. */
    public static Optional<File> adb() {
        Path path = directory().resolve("platform-tools").resolve(adbName());
        return path.toFile().isFile() ? Optional.of(path.toFile()) : Optional.empty();
    }

    private static String adbName() {
        return windows() ? "adb.exe" : "adb";
    }

    /**
     * Downloads the pinned {@code scrcpy-server} if it is not already there. Returns whether it is there now.
     *
     * <p>This is the one download in the stack that a caller may make without a user clicking anything: a bot
     * running headless has no dialog to offer, and the alternative is that a published bot never gets the fast
     * path. It is 0.7 MB, Apache-2.0, and digest-pinned. {@link #installPlatformTools} is deliberately not in
     * that category — it is Google-licensed, 9–16 MB, and only Studio ever asks for it.
     */
    public static boolean installScrcpyServer(Downloads.Progress progress) {
        return Downloads.fetch(SCRCPY_SERVER, scrcpyServerPath(), progress);
    }

    /**
     * Downloads and unpacks platform-tools for this OS. Returns whether an {@code adb} is now installed.
     *
     * <p>The zip is fetched into the tools directory, extracted there (it carries its own
     * {@code platform-tools/} root) and then deleted — keeping a 9 MB archive next to the 9 MB it unpacked to
     * would double the cost of the one thing being measured here in megabytes.
     */
    public static boolean installPlatformTools(Downloads.Progress progress) {
        Downloads.Remote remote = platformTools();
        if (remote == null) {
            return false;
        }
        Path archive = directory().resolve("platform-tools.zip");
        if (!Downloads.fetch(remote, archive, progress)) {
            return false;
        }
        boolean extracted = Unzip.extract(archive, directory());
        try {
            Files.deleteIfExists(archive);
        } catch (Exception ignored) {
            // A leftover archive costs disk, not correctness; the extracted tree is what anything looks at.
        }
        return extracted && adb().isPresent();
    }

    /** The platform-tools pin for the running OS, or {@code null} on one Google does not publish for. */
    public static Downloads.Remote platformTools() {
        return platformTools(System.getProperty("os.name", ""));
    }

    /**
     * The platform-tools pin for one OS. Package-visible and taking the OS name so all three are asserted from
     * whichever machine the tests run on.
     *
     * <p>Digests are SHA-1 because that is the only algorithm Google publishes in its own
     * {@code repository2-3.xml} manifest. Weak as a hash, adequate as the integrity check it is doing on top
     * of a TLS connection to {@code dl.google.com}.
     */
    static Downloads.Remote platformTools(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return platformToolsRemote("win", "e03e78b1d80b396f1c3358e31251cb31740e1110", 8_044_989);
        }
        if (os.contains("mac")) {
            return platformToolsRemote("darwin", "6ae73f4de6452dc57e62ec02b68eed92a4c21661", 16_110_554);
        }
        if (os.contains("nux") || os.contains("nix")) {
            return platformToolsRemote("linux", "477254aa5f903c15cf51001717bdf347fb6b53e0", 9_054_187);
        }
        return null;
    }

    private static Downloads.Remote platformToolsRemote(String suffix, String sha1, long size) {
        return new Downloads.Remote(
                "https://dl.google.com/android/repository/platform-tools_r"
                        + PLATFORM_TOOLS_REVISION + "-" + suffix + ".zip",
                Downloads.Digest.SHA_1, sha1, size);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
