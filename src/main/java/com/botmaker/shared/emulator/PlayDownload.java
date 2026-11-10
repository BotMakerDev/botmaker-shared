package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Spawn;
import com.botmaker.shared.tools.ManagedTools;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gets an Android game's own Google Play files onto this computer, into the {@link ApkLibrary}, with goopdl: the base
 * APK, its splits (install-time asset packs are splits too) and its OBB files, through Aurora Store's anonymous
 * accounts, with no Play app and no Google account (2026-11-01, the user's choice: second after the library).
 *
 * <p>goopdl runs from the venv BotMaker owns ({@link ManagedTools#installGoopdl}). The tokens it fetches stay in its
 * own files under the user's home; BotMaker never reads them, and gives goopdl blank account variables so a
 * personal account set in the environment is never used. When those accounts give nothing (none to give, Play's
 * 429, nothing for any processor), the account the user signed in to BotMaker ({@link GoogleAccount}) tries again
 * (2026-11-10, the user's call). The files are packed into one {@code .xapk} — APKs at its
 * root, OBBs under {@code Android/obb/<package>/} — which {@link EmulatorInstall#fromFile} installs together.
 *
 * <p>Blocking, and interruptible.
 */
public final class PlayDownload {

    /** A game can be gigabytes; past this one processor's download is given up on. */
    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    /** {@code goopdl info}: one page of details. */
    private static final Duration INFO_TIMEOUT = Duration.ofMinutes(3);

    /** goopdl's exit when Aurora's dispenser gave no account: every processor would fail the same way. */
    static final int NO_ACCOUNT = 3;
    /** goopdl's exit when Google Play answered 429: trying again now only makes it longer. */
    static final int RATE_LIMITED = 5;

    private PlayDownload() {}

    /**
     * Downloads {@code packageName}'s newest Play version for a device whose processors are {@code abis}, best
     * first ({@code ro.product.cpu.abilist}), trying the next processor when Play has none for one: Waydroid lists
     * {@code x86_64} first and still runs an ARM-only game. Installs goopdl first when there is none.
     */
    public static ApkDownload.Result fetch(String packageName, List<String> abis, ApkLibrary library,
                                          Consumer<String> progress) {
        if (packageName == null || !PlayStoreSearch.PACKAGE.matcher(packageName).matches()) {
            return ApkDownload.Result.failed("\"" + packageName + "\" isn't an Android package name.");
        }
        AtomicReference<String> why = new AtomicReference<>("");
        Optional<Path> goopdl = goopdl(progress, why::set);
        if (goopdl.isEmpty()) return ApkDownload.Result.failed(why.get());
        Tried anonymous = attempt(goopdl.get(), packageName, abis, library, progress, ENVIRONMENT);
        if (!anonymous.another()) return anonymous.result();
        Optional<GoogleAccount.Account> account = GoogleAccount.account();
        if (account.isEmpty()) return anonymous.result();
        report(progress, anonymous.result().message() + " Trying again with your Google account…");
        ApkDownload.Result signed = attempt(goopdl.get(), packageName, abis, library, progress,
                environment(account.get())).result();
        return signed.ok() ? signed : ApkDownload.Result.failed(anonymous.result().message()
                + " With your Google account: " + signed.message());
    }

    /** One pass over the processors; {@code another} when a pass with other credentials may still get the game. */
    private record Tried(ApkDownload.Result result, boolean another) {}

    private static Tried attempt(Path goopdl, String packageName, List<String> abis, ApkLibrary library,
                                 Consumer<String> progress, Map<String, String> env) {
        String failure = "";
        for (String arch : archs(abis)) {
            Path out;
            try {
                out = Files.createTempDirectory("botmaker-goopdl-");
            } catch (IOException e) {
                return new Tried(ApkDownload.Result.failed("Couldn't make a folder to download into: "
                        + e.getMessage()), false);
            }
            try {
                report(progress, "Downloading " + packageName + " from Google Play for " + arch + "…");
                Ran done = run(command(goopdl, packageName, arch, out), TIMEOUT, env, progress);
                if (done == null) return new Tried(ApkDownload.Result.failed("Google Play's download of "
                        + packageName + " took over " + TIMEOUT.toMinutes() + " minutes."), false);
                if (done.exit() == 0) {
                    try {
                        Path xapk = out.resolve(packageName + ".xapk");
                        pack(out, packageName, xapk);
                        ApkLibrary.Entry entry = library.add(xapk);
                        return new Tried(new ApkDownload.Result(Optional.of(entry), "Downloaded " + packageName
                                + " " + entry.version() + " from Google Play."), false);
                    } catch (IOException e) {
                        // What this processor got isn't an app: the next one may be.
                        failure = e.getMessage();
                        continue;
                    }
                }
                failure = done.said();
                Optional<String> stop = stop(done.exit(), failure);
                if (stop.isPresent()) return new Tried(ApkDownload.Result.failed(stop.get()), true);
            } catch (IOException e) {
                return new Tried(ApkDownload.Result.failed(e.getMessage()), false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Tried(ApkDownload.Result.failed("Stopped downloading " + packageName + "."), false);
            } finally {
                deleteTree(out);
            }
        }
        return new Tried(ApkDownload.Result.failed("Google Play gave no file for " + packageName
                + (failure.isBlank() ? "." : ": " + failure)), true);
    }

    /**
     * The {@code versionCode} Google Play serves for {@code packageName} to the first of {@code abis} it serves, read
     * with {@code goopdl info} and nothing downloaded; empty when Play didn't say. What an update asks before it
     * downloads a whole game.
     */
    public static OptionalLong newestVersion(String packageName, List<String> abis, Consumer<String> progress) {
        if (packageName == null || !PlayStoreSearch.PACKAGE.matcher(packageName).matches()) return OptionalLong.empty();
        Optional<Path> goopdl = goopdl(progress, why -> report(progress, why));
        if (goopdl.isEmpty()) return OptionalLong.empty();
        report(progress, "Asking Google Play for " + packageName + "'s newest version…");
        Asked anonymous = newestVersion(goopdl.get(), packageName, abis, progress, ENVIRONMENT);
        if (!anonymous.another()) return anonymous.code();
        Optional<GoogleAccount.Account> account = GoogleAccount.account();
        if (account.isEmpty()) return anonymous.code();
        report(progress, "Asking again with your Google account…");
        return newestVersion(goopdl.get(), packageName, abis, progress, environment(account.get())).code();
    }

    /**
     * What {@code goopdl info} answered; {@code another} only when the anonymous accounts were the trouble (none
     * given, 429), not a timeout or a game Play serves to no processor.
     */
    private record Asked(OptionalLong code, boolean another) {}

    private static Asked newestVersion(Path goopdl, String packageName, List<String> abis,
                                       Consumer<String> progress, Map<String, String> env) {
        for (String arch : archs(abis)) {
            try {
                AtomicReference<String> version = new AtomicReference<>("");
                Ran done = run(List.of(goopdl.toString(), "info", packageName, "--arch", arch), INFO_TIMEOUT, env,
                        line -> { if (line.startsWith("Version ")) version.set(line); });
                if (done == null) return new Asked(OptionalLong.empty(), false);
                OptionalLong code = versionCode(version.get());
                if (done.exit() == 0 && code.isPresent()) return new Asked(code, false);
                Optional<String> stop = stop(done.exit(), done.said());
                if (stop.isPresent()) {
                    report(progress, stop.get());
                    return new Asked(OptionalLong.empty(), true);
                }
            } catch (IOException e) {
                return new Asked(OptionalLong.empty(), false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Asked(OptionalLong.empty(), false);
            }
        }
        return new Asked(OptionalLong.empty(), false);
    }

    private static final Pattern VERSION = Pattern.compile("^Version\\s.*\\((\\d+)\\)\\s*$");

    /**
     * The code in {@code goopdl info}'s version row, its box gone ({@code Version 5.300.0 (53000000)}); empty for
     * {@code (0)}, which is Play serving nothing to that processor (an ARM-only game asked for x86_64). Pure.
     */
    static OptionalLong versionCode(String row) {
        Matcher m = VERSION.matcher(row == null ? "" : row.strip());
        if (!m.matches()) return OptionalLong.empty();
        long code = Long.parseLong(m.group(1));
        return code > 0 ? OptionalLong.of(code) : OptionalLong.empty();
    }

    /** Why no other processor is worth trying after an exit, when none is. */
    private static Optional<String> stop(int exit, String said) {
        if (exit == NO_ACCOUNT) return Optional.of("Aurora's anonymous accounts gave none for now: " + said);
        if (exit == RATE_LIMITED) return Optional.of("Google Play is limiting downloads for now: try again later.");
        return Optional.empty();
    }

    /** BotMaker's goopdl, installed first when there is none; empty with {@code why} told the reason. */
    private static Optional<Path> goopdl(Consumer<String> progress, Consumer<String> why) {
        if (ManagedTools.goopdlInstalled().isEmpty()) report(progress, "Getting goopdl, the Google Play downloader…");
        StringBuilder said = new StringBuilder();
        Optional<Path> goopdl = ManagedTools.installGoopdl(said::append);
        if (goopdl.isEmpty()) why.accept(said.isEmpty() ? "goopdl couldn't be installed." : said.toString());
        return goopdl;
    }

    /** How goopdl exited, and its last line with words. */
    private record Ran(int exit, String said) {}

    /** Runs goopdl, passing on each line with words; {@code null} past {@code timeout}. */
    private static Ran run(List<String> command, Duration timeout, Map<String, String> env,
                           Consumer<String> progress) throws IOException, InterruptedException {
        Diag.log("[Apk] " + String.join(" ", command)
                + (env.get("GOOPDL_ACCOUNT_EMAIL").isEmpty() ? "" : " (with the Google account)"));
        AtomicReference<String> said = new AtomicReference<>("");
        Spawn.Completed done = Spawn.streamed(timeout, command, env, line -> {
            String words = words(line);
            if (words.isEmpty()) return;
            said.set(words);
            report(progress, words);
        });
        return done == null ? null : new Ran(done.exitCode(), said.get());
    }

    /**
     * Blank account variables, so goopdl stays anonymous whatever the environment holds; no colours, a wide
     * terminal, so each line it writes reads as one line.
     */
    static final Map<String, String> ENVIRONMENT = environment(new GoogleAccount.Account("", ""));

    /**
     * {@link #ENVIRONMENT} with {@code account} as goopdl's direct Google account, when the anonymous ones gave nothing
     * ({@link GoogleAccount}). Pure.
     */
    static Map<String, String> environment(GoogleAccount.Account account) {
        return Map.of("GOOPDL_ACCOUNT_EMAIL", account.email(), "GOOPDL_AAS_TOKEN", account.token(),
                "NO_COLOR", "1", "TERM", "dumb", "COLUMNS", "160", "PYTHONIOENCODING", "utf-8");
    }

    /** goopdl's command line for one package and processor: splits and extras (asset packs, OBBs) included. Pure. */
    static List<String> command(Path goopdl, String packageName, String arch, Path out) {
        return List.of(goopdl.toString(), "download", packageName, "-o", out.toString(), "-a", arch, "--splits",
                "--extras");
    }

    /**
     * goopdl's device types for Android's ABIs, in the device's order, each once; an ABI goopdl has no device for
     * ({@code armeabi}, {@code riscv64}) is left out, and no ABI at all is {@code arm64}, goopdl's own default. Pure.
     */
    static List<String> archs(List<String> abis) {
        Set<String> archs = new LinkedHashSet<>();
        for (String abi : abis == null ? List.<String>of() : abis) {
            switch (abi.strip().toLowerCase(Locale.ROOT)) {
                case "arm64-v8a" -> archs.add("arm64");
                case "armeabi-v7a" -> archs.add("armv7");
                case "x86_64" -> archs.add("x86_64");
                case "x86" -> archs.add("x86");
                default -> { }
            }
        }
        return archs.isEmpty() ? List.of("arm64") : List.copyOf(archs);
    }

    /**
     * Packs what goopdl left in {@code folder} into {@code xapk}: every {@code .apk} at the root, every {@code .obb}
     * under {@code Android/obb/<package>/}; anything else (its {@code .dm}) is left out. Stored, not compressed again:
     * APKs and OBBs are compressed already, and a game's are gigabytes.
     *
     * @throws IOException when the folder holds no APK
     */
    static void pack(Path folder, String packageName, Path xapk) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(folder)) {
            list.filter(Files::isRegularFile).filter(file -> !file.equals(xapk)).sorted().forEach(files::add);
        }
        if (files.stream().noneMatch(file -> lower(file).endsWith(".apk"))) {
            throw new IOException("Google Play's download of " + packageName + " holds no APK.");
        }
        try (OutputStream raw = Files.newOutputStream(xapk); ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (Path file : files) {
                String name = file.getFileName().toString();
                String entry = lower(file).endsWith(".apk") ? name
                        : lower(file).endsWith(".obb") ? "Android/obb/" + packageName + "/" + name : null;
                if (entry == null) continue;
                zip.putNextEntry(new ZipEntry(entry));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    /**
     * A line goopdl wrote without the box it draws around an error or a table ({@code │ Missing option… │}); blank
     * for a line that is only the box. Pure.
     */
    static String words(String line) {
        String bare = line == null ? "" : line.replaceAll("[\\u2500-\\u257F]", " ").strip();
        return bare.chars().anyMatch(Character::isLetter) ? bare.replaceAll("\\s{2,}", " ") : "";
    }

    private static String lower(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static void deleteTree(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temp folder is not worth failing over
        }
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null && message != null && !message.isBlank()) progress.accept(message);
    }
}
