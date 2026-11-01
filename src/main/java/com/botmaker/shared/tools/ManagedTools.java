package com.botmaker.shared.tools;

import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

    /** The apkeep release pinned below. */
    public static final String APKEEP_VERSION = "1.1.0";

    /**
     * EFF's {@code apkeep}, which downloads an Android app by its package (2026-11-01, the user's pick for getting a
     * game's APK without a browser). One binary per OS and processor, digests taken from the release's own files.
     * Unlike scrcpy-server it is fetched only when a person asks for a download; macOS has no release binary, so
     * there it is the user's own ({@code brew}/{@code cargo}) or none.
     */
    static Downloads.Remote apkeep(String osName, String arch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String cpu = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        boolean x64 = cpu.equals("amd64") || cpu.equals("x86_64");
        boolean arm64 = cpu.equals("aarch64") || cpu.equals("arm64");
        if (os.contains("win") && x64) {
            return apkeepRemote("x86_64-pc-windows-msvc.exe",
                    "fb61e52fa48c321bbbb6d899dd06abf1705e3dbd74f07813bd2784279766ff6f", 12_098_560);
        }
        if (os.contains("nux") && x64) {
            return apkeepRemote("x86_64-unknown-linux-gnu",
                    "badd7ad9fa7d2f32abe01eaf33b7ff086361a95bbc000a33bf44b630d4bf2140", 16_939_544);
        }
        if (os.contains("nux") && arm64) {
            return apkeepRemote("aarch64-unknown-linux-gnu",
                    "1a2e959842bbca4bc8db455b8132cc6f37534d70f2384409096268d8fc442ae8", 15_811_960);
        }
        return null;
    }

    private static Downloads.Remote apkeepRemote(String target, String sha256, long size) {
        return new Downloads.Remote("https://github.com/EFForg/apkeep/releases/download/" + APKEEP_VERSION
                + "/apkeep-" + target, Downloads.Digest.SHA_256, sha256, size);
    }

    /** Where BotMaker's own apkeep goes, whether or not it is there. */
    public static Path apkeepPath() {
        return directory().resolve("apkeep").resolve((windows() ? "apkeep-" + APKEEP_VERSION + ".exe"
                : "apkeep-" + APKEEP_VERSION));
    }

    /** BotMaker's own apkeep when it was installed, else one on the user's {@code PATH}. */
    public static Optional<Path> apkeepInstalled() {
        Path own = apkeepPath();
        if (Files.isExecutable(own)) return Optional.of(own);
        return Executables.find("apkeep").map(File::toPath);
    }

    /**
     * Downloads the pinned apkeep for this computer when none is installed; returns it, or empty on an OS with no
     * release binary or when the download failed.
     */
    public static Optional<Path> installApkeep(Downloads.Progress progress) {
        Optional<Path> installed = apkeepInstalled();
        if (installed.isPresent()) return installed;
        Downloads.Remote remote = apkeep(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        Path target = apkeepPath();
        if (remote == null || !Downloads.fetch(remote, target, progress)) return Optional.empty();
        if (!target.toFile().setExecutable(true, true)) return Optional.empty();
        return Optional.of(target);
    }

    /** The goopdl release pinned below. */
    public static final String GOOPDL_VERSION = "1.3.1";

    /**
     * goopdl and everything it imports, each wheel pinned by its PyPI digest (2026-11-01): goopdl gets Google Play's
     * own files for a package through Aurora Store's anonymous accounts (the user's pick, phase 6). pip installs
     * exactly these and nothing else ({@code --require-hashes --no-deps}); a digest that no longer matches fails the
     * install, never fetches another version. The set was read from a working install on Python 3.12; Python 3.10
     * would also need {@code exceptiongroup}, so 3.11 is the floor.
     */
    static final List<String> GOOPDL_REQUIREMENTS = List.of(
            "goopdl==1.3.1 --hash=sha256:47a4920ecce25b695ac887645a3e952e9b7a5308aba17d2fb049ae20af76328a",
            "annotated-doc==0.0.5 --hash=sha256:117bac03a25ede5df5440e855b32d556049ca169ead221505badf432fed4b101",
            "anyio==4.16.0 --hash=sha256:c66ae634e0b8228396b801741beb3294326d4b6102f7bba4041c0dfb58c29026",
            "certifi==2026.10.23 --hash=sha256:e9b54de76e530bb6cf0ccd036787103acb58fd42ba930ea8bf948ddbaa2f61d1",
            "h11==0.16.0 --hash=sha256:63cf8bbe7522de3bf65932fda1d9c2772064ffb3dae62d55932da54b31cb6c86",
            "httpcore==1.0.9 --hash=sha256:2d400746a40668fc9dec9810239072b40b4484b640a8c38fd654a024c7a1bf55",
            "httpx==0.28.1 --hash=sha256:d909fcccc110f8c7faf814ca82a9a4d816bc5a6dbfea25d6591d6985b8ba59ad",
            "idna==3.20 --hash=sha256:ab7ae7122974553370f0bdb919e1a960b2cd1bc1ef0276416d896db81c14582c",
            "markdown-it-py==4.2.0 --hash=sha256:9f7ebbcd14fe59494226453aed97c1070d83f8d24b6fc3a3bcf9a38092641c4a",
            "mdurl==0.1.2 --hash=sha256:84008a41e51615a49fc9966191ff91509e3c40b939176e643fd50a5c2196b8f8",
            "Pygments==2.21.0 --hash=sha256:2363c69b61c4a97c838da3b130dcd6468f4848992b21a82f2a63ec34377137d9",
            "rich==15.0.0 --hash=sha256:33bd4ef74232fb73fe9279a257718407f169c09b78a87ad3d296f548e27de0bb",
            "shellingham==1.5.4 --hash=sha256:7ecfff8f2fd72616f7481040475a65b2bf8af90a56c89140852d1120324e8686",
            "typer==0.27.3 --hash=sha256:e50022f28b82a86313e54501317a1db64bf8f8d036ff8cfe5ca7e47675454aff",
            "typing_extensions==4.16.0 --hash=sha256:481caa481374e813c1b176ada14e97f1f67a4539ce9cfeb3f350d78d6370c2e8",
            "websocket-client==1.9.2 --hash=sha256:e1a673830a9c7bfa47b1cd3d5e4178f4c9651d80a4eab02c9c23a1c3ec6250ce");

    /** The file whose presence says pip finished: a venv pip failed in is no goopdl. */
    private static final String GOOPDL_DONE = "botmaker-installed";

    /** The venv BotMaker owns for goopdl, whether or not it is there. */
    public static Path goopdlVenv() {
        return directory().resolve("goopdl-" + GOOPDL_VERSION);
    }

    /** goopdl's command in a venv at {@code venv}. */
    static Path goopdlIn(Path venv, boolean windows) {
        return windows ? venv.resolve("Scripts").resolve("goopdl.exe") : venv.resolve("bin").resolve("goopdl");
    }

    /**
     * BotMaker's own goopdl when its venv is complete. Never one on the user's {@code PATH}: theirs may be another
     * version, and its token files are theirs to manage.
     */
    public static Optional<Path> goopdlInstalled() {
        Path venv = goopdlVenv();
        Path goopdl = goopdlIn(venv, windows());
        return Files.isRegularFile(venv.resolve(GOOPDL_DONE)) && Files.isRegularFile(goopdl) ? Optional.of(goopdl)
                : Optional.empty();
    }

    /** pip's command line that installs {@link #GOOPDL_REQUIREMENTS} from {@code requirements}. Pure, for the tests. */
    static List<String> goopdlPip(Path python, Path requirements) {
        return List.of(python.toString(), "-m", "pip", "install", "--disable-pip-version-check", "--no-input",
                "--require-hashes", "--no-deps", "--only-binary=:all:", "-r", requirements.toString());
    }

    /**
     * Makes BotMaker's goopdl venv with this computer's Python 3.11 or newer when there is none; returns goopdl, or
     * empty with {@code why} told the reason. Blocking: pip downloads about 3 MB. One at a time: the dialog and the
     * assistant installing together would each delete the other's half-made venv.
     */
    public static synchronized Optional<Path> installGoopdl(Consumer<String> why) {
        Optional<Path> installed = goopdlInstalled();
        if (installed.isPresent()) return installed;
        Optional<Path> python = python311();
        if (python.isEmpty()) {
            why.accept("goopdl needs Python 3.11 or newer, and none is on this computer.");
            return Optional.empty();
        }
        Path venv = goopdlVenv();
        try {
            deleteTree(venv);
            Spawn.Completed made = Spawn.run(PIP_TIMEOUT, python.get().toString(), "-m", "venv", venv.toString());
            if (made == null || !made.ok()) {
                why.accept("Python couldn't make goopdl's venv: " + (made == null ? "it took too long" : made.output()));
                return Optional.empty();
            }
            Path requirements = venv.resolve("requirements.txt");
            Files.writeString(requirements, String.join("\n", GOOPDL_REQUIREMENTS) + "\n");
            Path venvPython = windows() ? venv.resolve("Scripts").resolve("python.exe")
                    : venv.resolve("bin").resolve("python");
            Spawn.Completed pip = Spawn.run(PIP_TIMEOUT, goopdlPip(venvPython, requirements));
            if (pip == null || !pip.ok()) {
                why.accept("pip couldn't install goopdl " + GOOPDL_VERSION + ": " + (pip == null ? "it took too long"
                        : lastLine(pip.output())));
                deleteTree(venv);
                return Optional.empty();
            }
            Files.writeString(venv.resolve(GOOPDL_DONE), GOOPDL_VERSION + "\n");
            return goopdlInstalled();
        } catch (IOException e) {
            why.accept("Couldn't install goopdl: " + e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            // The venv has no done-file yet: the next install starts it again.
            Thread.currentThread().interrupt();
            why.accept("Stopped installing goopdl.");
            return Optional.empty();
        }
    }

    private static final Duration PIP_TIMEOUT = Duration.ofMinutes(5);

    /** {@code python3}, else {@code python}, when it is 3.11 or newer. */
    private static Optional<Path> python311() {
        for (String name : List.of("python3", "python")) {
            Optional<File> found = Executables.find(name);
            if (found.isEmpty()) continue;
            try {
                Spawn.Completed version = Spawn.run(Duration.ofSeconds(10), found.get().getPath(), "-c",
                        "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)");
                if (version != null && version.ok()) return Optional.of(found.get().toPath());
            } catch (IOException e) {
                // try the next name
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String lastLine(String output) {
        String[] lines = output == null ? new String[0] : output.strip().split("\\R");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
