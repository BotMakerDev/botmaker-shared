package com.botmaker.shared.emulator;

import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Android games BotMaker keeps on this computer, one folder per package: {@code <data>/apps/<package>/}, each
 * version a file named {@code <versionCode>_<versionName>.<apk|xapk|apks>}. A file the user downloaded is added
 * here, and so is one {@link ApkDownload} fetched; an install or update takes the newest. Only the {@link #KEEP}
 * newest versions of a package stay: the one installed, and the one before it to go back to.
 *
 * <p>The file names are the index: nothing else is written, so a folder copied or cleaned by hand still reads.
 */
public final class ApkLibrary {

    /** How many versions of one package are kept. */
    public static final int KEEP = 2;

    private static final Pattern FILE = Pattern.compile("(\\d+)(?:_(.*))?\\.(apk|xapk|apks)");
    private static final Pattern PACKAGE = PlayStoreSearch.PACKAGE;

    private final Path root;

    public ApkLibrary(Path root) {
        this.root = root;
    }

    /** The library under BotMaker's data folder ({@code ~/.local/share/botmaker/apps} on Linux). */
    public static ApkLibrary shared() {
        return new ApkLibrary(UserDirs.data().resolve("apps"));
    }

    public Path root() {
        return root;
    }

    /**
     * One version of one game in the library.
     *
     * @param packageName the app's package
     * @param versionCode what Android compares, {@code 0} when the file said none
     * @param versionName the version people read, or {@code null}
     * @param file        the file itself
     */
    public record Entry(String packageName, long versionCode, String versionName, Path file) {

        /** The version as a person reads it. */
        public String version() {
            return versionName != null ? versionName : versionCode > 0 ? Long.toString(versionCode) : "unknown";
        }

        /** Whether this is newer than what is installed ({@code installed} {@code -1}: nothing is). */
        public boolean newerThan(long installed) {
            return versionCode > installed;
        }
    }

    /** Every package with at least one version here, sorted. */
    public List<String> packages() {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> folders = Files.list(root)) {
            return folders.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(name -> PACKAGE.matcher(name).matches() && !versions(name).isEmpty()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** {@code packageName}'s versions here, newest first. */
    public List<Entry> versions(String packageName) {
        Path folder = folder(packageName);
        if (folder == null || !Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> entry(packageName, file)).flatMap(Optional::stream)
                    .sorted(Comparator.comparingLong(Entry::versionCode).reversed()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** {@code packageName}'s newest version here. */
    public Optional<Entry> newest(String packageName) {
        return versions(packageName).stream().findFirst();
    }

    /**
     * Copies {@code file} (an {@code .apk}, {@code .xapk} or {@code .apks}) into the library under the package and
     * version it says it is, then keeps only the {@link #KEEP} newest. A version already here is replaced.
     *
     * @throws IOException with a sentence a UI shows, when the file isn't an Android app or can't be copied
     */
    public Entry add(Path file) throws IOException {
        String extension = extension(file);
        long code;
        String name;
        String pkg;
        try (ApkFile apk = ApkFile.open(file)) {
            pkg = apk.packageName();
            code = apk.versionCode();
            name = apk.versionName();
        }
        Path folder = folder(pkg);
        if (folder == null) throw new IOException(file.getFileName() + " names no valid package (" + pkg + ").");
        Files.createDirectories(folder);
        Path target = folder.resolve(fileName(code, name, extension));
        if (!target.equals(file)) {
            Path partial = folder.resolve(target.getFileName() + ".part");
            Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        Entry added = new Entry(pkg, code, name, target);
        prune(pkg, added);
        return added;
    }

    /** Deletes every version of {@code packageName} but the {@link #KEEP} newest; returns those deleted. */
    public List<Entry> prune(String packageName) {
        return prune(packageName, null);
    }

    /**
     * {@link #prune(String)}, never deleting {@code keep}: the file just added is there to be installed, even when
     * it is older than the two kept. Versions are counted by code, so two files of one version (an {@code .apk}
     * and an {@code .xapk}) are one version and both stay.
     */
    private List<Entry> prune(String packageName, Entry keep) {
        List<Long> kept = versions(packageName).stream().map(Entry::versionCode).distinct().limit(KEEP).toList();
        List<Entry> deleted = new ArrayList<>();
        for (Entry old : versions(packageName)) {
            if (kept.contains(old.versionCode()) || (keep != null && old.file().equals(keep.file()))) continue;
            if (delete(old)) deleted.add(old);
        }
        return deleted;
    }

    /** Deletes one version; returns whether it was there. */
    public boolean delete(Entry entry) {
        try {
            return Files.deleteIfExists(entry.file());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The newest {@code .apk}/{@code .xapk}/{@code .apks} in {@code folder} changed after {@code since} that is
     * {@code packageName}: what a browser left in Downloads after the user picked it on a store page. Files still
     * being written ({@code .crdownload}, {@code .part}) aren't apps yet and are skipped.
     */
    public static Optional<Path> downloaded(Path folder, String packageName, Instant since) {
        if (folder == null || !Files.isDirectory(folder)) return Optional.empty();
        List<Path> candidates;
        try (Stream<Path> files = Files.list(folder)) {
            candidates = files.filter(Files::isRegularFile).filter(file -> extensionOf(file) != null)
                    .filter(file -> modified(file).isAfter(since))
                    .sorted(Comparator.comparing(ApkLibrary::modified).reversed()).toList();
        } catch (IOException e) {
            return Optional.empty();
        }
        for (Path file : candidates) {
            try (ApkFile apk = ApkFile.open(file)) {
                if (apk.packageName().equals(packageName)) return Optional.of(file);
            } catch (IOException notAnApp) {
                // a half-written or unrelated file: the next one
            }
        }
        return Optional.empty();
    }

    /** The user's Downloads folder, where a browser saves what a store page gives. */
    public static Path downloadsFolder() {
        String xdg = System.getenv("XDG_DOWNLOAD_DIR");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home", ""), "Downloads");
    }

    /** The library's name for one version's file. Pure, for the tests. */
    static String fileName(long code, String name, String extension) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        return code + (safe.isBlank() ? "" : "_" + safe) + "." + extension;
    }

    /** One file of the library read back as an entry, or empty when the name isn't one this wrote. Pure. */
    static Optional<Entry> entry(String packageName, Path file) {
        Matcher m = FILE.matcher(file.getFileName().toString());
        if (!m.matches()) return Optional.empty();
        try {
            return Optional.of(new Entry(packageName, Long.parseLong(m.group(1)),
                    m.group(2) == null || m.group(2).isBlank() ? null : m.group(2), file));
        } catch (NumberFormatException tooLong) {
            return Optional.empty();   // a hand-named file no version fits
        }
    }

    private Path folder(String packageName) {
        return packageName != null && PACKAGE.matcher(packageName).matches() ? root.resolve(packageName) : null;
    }

    private static String extension(Path file) throws IOException {
        String extension = extensionOf(file);
        if (extension == null) throw new IOException(file.getFileName() + " isn't an .apk, .xapk or .apks file.");
        return extension;
    }

    /** {@code apk}, {@code xapk} or {@code apks}, lower case, or {@code null} for any other file. */
    private static String extensionOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : List.of("apk", "xapk", "apks")) {
            if (name.endsWith("." + extension)) return extension;
        }
        return null;
    }

    private static Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
