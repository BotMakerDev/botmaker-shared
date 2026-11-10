package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Spawn;
import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.ManagedTools;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Gets an Android game's APK or XAPK onto this computer, into the {@link ApkLibrary}: by EFF's {@code apkeep} from
 * APKPure, with no browser and no account, or — when apkeep can't — by a store page the user picks the file on, as
 * ReVanced Manager does (2026-11-01, the user's choice: apkeep first, the page as the fallback).
 *
 * <p><b>APKPure is a third-party mirror</b>, and apkeep refuses it until its dangers are acknowledged: APKPure has
 * been found serving modified apps. {@link #fetch} therefore takes the user's acknowledgement as an argument and
 * never assumes it; a UI asks once and remembers the answer.
 *
 * <p>Blocking, and interruptible.
 */
public final class ApkDownload {

    /** A game is tens to hundreds of megabytes; past this the download is given up on. */
    private static final Duration TIMEOUT = Duration.ofMinutes(20);

    /** apkeep's own page on what APKPure may do, which a UI links when it asks. */
    public static final String DANGERS = "https://github.com/EFForg/apkeep/blob/master/USAGE-apkpure.md";

    /** The file whose presence is the user's OK to download from APKPure, under {@link UserDirs#config()}. */
    private static final String ACKNOWLEDGED = "apkpure-acknowledged";

    private ApkDownload() {}

    /**
     * Whether the user accepted APKPure's risk ({@link #DANGERS}) on this computer. Only the user gives it, in Studio;
     * the assistant reads it and never sets it.
     */
    public static boolean acknowledged() {
        return acknowledged(UserDirs.config());
    }

    static boolean acknowledged(Path config) {
        return Files.isRegularFile(config.resolve(ACKNOWLEDGED));
    }

    /** Records the user's answer: {@code yes} keeps it, {@code false} withdraws it. */
    public static void acknowledge(boolean yes) throws IOException {
        acknowledge(UserDirs.config(), yes);
    }

    static void acknowledge(Path config, boolean yes) throws IOException {
        Path file = config.resolve(ACKNOWLEDGED);
        if (!yes) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(config);
        Files.writeString(file, "Downloading from APKPure accepted on " + LocalDate.now() + ", knowing "
                + DANGERS + "\n");
    }

    /**
     * What a download did.
     *
     * @param entry   the version now in the library, or empty when nothing was downloaded
     * @param message a sentence a UI shows as it is
     */
    public record Result(Optional<ApkLibrary.Entry> entry, String message) {

        public boolean ok() {
            return entry.isPresent();
        }

        static Result failed(String message) {
            return new Result(Optional.empty(), message);
        }
    }

    /**
     * Downloads {@code packageName}'s newest version from APKPure into {@code library}, for a device whose processors
     * are {@code abis}, best first ({@code x86_64}, {@code arm64-v8a}…; none: apkeep's default), trying the next when
     * APKPure has nothing for one: BlueStacks and LDPlayer list {@code x86_64} first and run an ARM-only game
     * (2026-11-10). Installs the pinned apkeep first when there is none.
     *
     * @param acknowledged whether the user accepted that APKPure is a third-party mirror ({@link #DANGERS})
     * @param progress     optional narration; apkeep's own lines come on a reader thread
     */
    public static Result fetch(String packageName, List<String> abis, boolean acknowledged, ApkLibrary library,
                               Consumer<String> progress) {
        if (packageName == null || !PlayStoreSearch.PACKAGE.matcher(packageName).matches()) {
            return Result.failed("\"" + packageName + "\" isn't an Android package name.");
        }
        if (!acknowledged) {
            return Result.failed("Downloading from APKPure needs your OK first: it is a third-party copy of Google "
                    + "Play that has been found serving modified apps (" + DANGERS + ").");
        }
        report(progress, "Getting apkeep, the downloader…");
        Optional<Path> apkeep = ManagedTools.installApkeep(Downloads.Progress.IGNORED);
        if (apkeep.isEmpty()) {
            return Result.failed("apkeep isn't installed and BotMaker has no copy for this computer: install it "
                    + "(cargo install apkeep), or download the game from a store page.");
        }
        String failure = "";
        for (String arch : archs(abis)) {
            Path out;
            try {
                out = Files.createTempDirectory("botmaker-apkeep-");
            } catch (IOException e) {
                return Result.failed("Couldn't make a folder to download into: " + e.getMessage());
            }
            try {
                report(progress, "Downloading " + packageName + " from APKPure"
                        + (arch.isEmpty() ? "" : " for " + arch) + "…");
                List<String> command = command(apkeep.get(), packageName, arch, out);
                Diag.log("[Apk] " + String.join(" ", command));
                Spawn.Completed done = Spawn.streamed(TIMEOUT, command, Map.of(), progress);
                if (done == null) {
                    return Result.failed("APKPure's download of " + packageName + " took over "
                            + TIMEOUT.toMinutes() + " minutes.");
                }
                Optional<Path> file = downloadedFile(out);
                if (file.isEmpty()) {
                    // Nothing for this processor: the next one may have it.
                    failure = done.ok() ? "" : done.output().isBlank() ? "apkeep exited with " + done.exitCode()
                            : done.output();
                    continue;
                }
                ApkLibrary.Entry entry = library.add(file.get());
                return new Result(Optional.of(entry), "Downloaded " + packageName + " " + entry.version() + ".");
            } catch (IOException e) {
                return Result.failed(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.failed("Stopped downloading " + packageName + ".");
            } finally {
                deleteTree(out);
            }
        }
        return Result.failed("APKPure gave no file for " + packageName
                + (failure.isBlank() ? "." : ": " + failure));
    }

    /**
     * The processors apkeep is asked for, in the device's order, each once; one that isn't an ABI (a comma would add
     * an option) is left out, and none at all is one run with apkeep's default, blank. Pure.
     */
    static List<String> archs(List<String> abis) {
        Set<String> archs = new LinkedHashSet<>();
        for (String abi : abis == null ? List.<String>of() : abis) {
            String arch = abi == null ? "" : abi.strip();
            if (arch.matches("[A-Za-z0-9_-]+")) archs.add(arch);
        }
        return archs.isEmpty() ? List.of("") : List.copyOf(archs);
    }

    /**
     * apkeep's command line for one package: APKPure, its dangers acknowledged, the device's processor. Pure, for
     * the tests.
     */
    static List<String> command(Path apkeep, String packageName, String abi, Path out) {
        // An ABI is letters, digits, '-' and '_'; anything else (a comma would add an option) is no ABI.
        String arch = abi == null ? "" : abi.strip();
        String options = "acknowledge_dangers=true" + (arch.matches("[A-Za-z0-9_-]+") ? ",arch=" + arch : "");
        return List.of(apkeep.toString(), "-a", packageName, "-d", "apk-pure", "-o", options, out.toString());
    }

    /**
     * The store pages a person picks {@code packageName}'s file on when apkeep can't: APKPure's and APKMirror's
     * search for the package. Pure.
     */
    public static List<String> pages(String packageName) {
        String q = URLEncoder.encode(packageName == null ? "" : packageName, StandardCharsets.UTF_8);
        return List.of("https://apkpure.com/search?q=" + q,
                "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=" + q);
    }

    /** The {@code .apk}/{@code .xapk}/{@code .apks} apkeep left in {@code out} ({@code <pkg>@<arch>.apk}). */
    static Optional<Path> downloadedFile(Path out) {
        try (Stream<Path> files = Files.list(out)) {
            return files.filter(Files::isRegularFile).filter(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apks");
            }).max(Comparator.comparingLong(ApkDownload::size));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteTree(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temp folder is not worth failing over
        }
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }
}
