// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Android games BotMaker keeps on this computer, one folder per package: {@code <data>/apps/<package>/}, each
 * version a file named {@code <versionCode>_<versionName>_<abi>.<apk|xapk|apks>}, the {@link CpuAbi} the one its
 * native code is built for, read out of the file. A name without a kind (a file with no native code, or one kept
 * before 2026-11-25) is {@link CpuAbi#ANY}, and serves every device. A file the user downloaded is added here, and so
 * is one {@link ApkDownload} fetched; an install or update takes the newest the device's processor runs. Only the
 * {@link #KEEP} newest versions of each kind stay: the one installed, and the one before it to go back to.
 *
 * <p>The file names are the index: nothing else is written, so a folder copied or cleaned by hand still reads. A
 * file being installed ({@link #installing}) is never deleted, by a prune or by the user.
 */
public final class ApkLibrary {

    /** How many versions of one package are kept, for each kind of processor. */
    public static final int KEEP = 2;

    private static final Pattern FILE = Pattern.compile("(\\d+)(?:_(.*))?\\.(apk|xapk|apks)");
    private static final Pattern PACKAGE = PlayStoreSearch.PACKAGE;
    /** The files installs read now, by real path, each with how many installs read it (this process only). */
    private static final Map<Path, Integer> IN_USE = new ConcurrentHashMap<>();

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
     * @param abi         the processor its native code is for, {@link CpuAbi#ANY} for every one
     * @param file        the file itself
     */
    public record Entry(String packageName, long versionCode, String versionName, CpuAbi abi, Path file) {

        /** The version as a person reads it. */
        public String version() {
            return versionName != null ? versionName : versionCode > 0 ? Long.toString(versionCode) : "unknown";
        }

        /** Whether this is newer than what is installed ({@code installed} {@code -1}: nothing is). */
        public boolean newerThan(long installed) {
            return versionCode > installed;
        }

        /** The file's size in bytes, {@code 0} when it is gone. */
        public long size() {
            try {
                return Files.size(file);
            } catch (IOException e) {
                return 0;
            }
        }
    }

    /** While open, its file is being installed: {@link #delete} refuses it and a prune leaves it. */
    public static final class Lease implements AutoCloseable {
        private final Path key;
        private boolean closed;

        private Lease(Path key) {
            this.key = key;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            IN_USE.computeIfPresent(key, (path, count) -> count <= 1 ? null : count - 1);
        }
    }

    /** Marks {@code file} as being installed until the lease is closed. */
    public static Lease installing(Path file) {
        Path key = key(file);
        IN_USE.merge(key, 1, Integer::sum);
        return new Lease(key);
    }

    /** Whether an install reads {@code file} now. */
    public static boolean inUse(Path file) {
        return IN_USE.containsKey(key(file));
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

    /** {@code packageName}'s versions here, of every kind, newest first. */
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

    /**
     * {@code packageName}'s newest version here that a device whose processors are {@code abis} runs, best first
     * ({@code ro.product.cpu.abilist}): of one version, the file built for the device's first kind wins over one for a
     * later kind, and both over {@link CpuAbi#ANY}. No {@code abis} (a device that didn't say) takes any file.
     */
    public Optional<Entry> newest(String packageName, List<String> abis) {
        List<CpuAbi> device = abis == null ? List.of() : abis.stream().map(CpuAbi::fromId)
                .filter(abi -> abi != CpuAbi.ANY && abi != CpuAbi.UNKNOWN).distinct().toList();
        return versions(packageName).stream()
                .filter(entry -> device.isEmpty() || device.stream().anyMatch(entry.abi()::serves))
                .min(Comparator.comparingLong(Entry::versionCode).reversed()
                        .thenComparingInt(entry -> entry.abi() == CpuAbi.ANY ? device.size()
                                : device.indexOf(entry.abi())));
    }

    /** The bytes every file of the library takes. */
    public long size() {
        return packages().stream().flatMap(pkg -> versions(pkg).stream()).mapToLong(Entry::size).sum();
    }

    /**
     * Copies {@code file} (an {@code .apk}, {@code .xapk} or {@code .apks}) into the library under the package,
     * version and processor it says it is, then keeps only the {@link #KEEP} newest of that kind. A version already
     * here is replaced.
     *
     * @throws IOException with a sentence a UI shows, when the file isn't an Android app or can't be copied
     */
    public Entry add(Path file) throws IOException {
        String extension = extension(file);
        long code;
        String name;
        String pkg;
        CpuAbi abi;
        try (ApkFile apk = ApkFile.open(file)) {
            pkg = apk.packageName();
            code = apk.versionCode();
            name = apk.versionName();
            abi = abiOf(apk.apks());
        }
        Path folder = folder(pkg);
        if (folder == null) throw new IOException(file.getFileName() + " names no valid package (" + pkg + ").");
        Files.createDirectories(folder);
        Path target = folder.resolve(fileName(code, name, abi, extension));
        if (!target.equals(file)) {
            if (inUse(target)) {
                throw new IOException(target.getFileName() + " is being installed now: add it again when that is done.");
            }
            Path partial = folder.resolve(target.getFileName() + ".part");
            Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        Entry added = new Entry(pkg, code, name, abi, target);
        prune(pkg, added);
        return added;
    }

    /** Deletes every version of {@code packageName} but the {@link #KEEP} newest of each kind; returns those deleted. */
    public List<Entry> prune(String packageName) {
        return prune(packageName, null);
    }

    /**
     * {@link #prune(String)}, never deleting {@code keep}: the file just added is there to be installed, even when
     * it is older than the two kept. Versions are counted by code within one kind, so two files of one version (an
     * {@code .apk} and an {@code .xapk}) are one version and both stay. A file being installed stays too.
     */
    private List<Entry> prune(String packageName, Entry keep) {
        List<Entry> all = versions(packageName);
        Map<CpuAbi, List<Long>> kept = all.stream().collect(Collectors.groupingBy(Entry::abi,
                Collectors.collectingAndThen(Collectors.toList(), entries -> entries.stream()
                        .map(Entry::versionCode).distinct().limit(KEEP).toList())));
        List<Entry> deleted = new ArrayList<>();
        for (Entry old : all) {
            if (kept.get(old.abi()).contains(old.versionCode())) continue;
            if (keep != null && old.file().equals(keep.file())) continue;
            try {
                if (delete(old)) deleted.add(old);
            } catch (IOException beingInstalled) {
                // pruned by a later add, once its install is done
            }
        }
        return deleted;
    }

    /**
     * Deletes one version's file; returns whether it was there. Deleting a file never uninstalls the app.
     *
     * @throws IOException with a sentence a UI shows, when an install reads it now or it can't be deleted
     */
    public boolean delete(Entry entry) throws IOException {
        if (inUse(entry.file())) {
            throw new IOException(entry.file().getFileName() + " is being installed now: delete it when that is done.");
        }
        return Files.deleteIfExists(entry.file());
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

    /**
     * The processor the native code of {@code apks} (one app's base and splits) is built for: the one kind under
     * their {@code lib/} folders, else {@link CpuAbi#ANY} (no native code, or code for several kinds). A kind this
     * build doesn't know counts as one more kind.
     */
    static CpuAbi abiOf(List<Path> apks) {
        Set<CpuAbi> kinds = EnumSet.noneOf(CpuAbi.class);
        for (Path apk : apks) {
            try (ZipFile zip = new ZipFile(apk.toFile())) {
                zip.stream().map(ZipEntry::getName).filter(name -> name.startsWith("lib/"))
                        .map(name -> name.split("/"))
                        .filter(parts -> parts.length > 2)
                        .forEach(parts -> kinds.add(CpuAbi.fromId(parts[1])));
            } catch (IOException notAZip) {
                // its kind is unknown: it adds none
            }
        }
        return kinds.size() == 1 && !kinds.contains(CpuAbi.UNKNOWN) ? kinds.iterator().next() : CpuAbi.ANY;
    }

    /** The library's name for one version's file; its name never holds {@code _}, which parts the kind. Pure. */
    static String fileName(long code, String name, CpuAbi abi, String extension) {
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9.-]", "-");
        String kind = abi == null || abi == CpuAbi.ANY || abi == CpuAbi.UNKNOWN ? "" : abi.id();
        return code + (safe.isBlank() && kind.isEmpty() ? "" : "_" + safe) + (kind.isEmpty() ? "" : "_" + kind)
                + "." + extension;
    }

    /** One file of the library read back as an entry, or empty when the name isn't one this wrote. Pure. */
    static Optional<Entry> entry(String packageName, Path file) {
        Matcher m = FILE.matcher(file.getFileName().toString());
        if (!m.matches()) return Optional.empty();
        long code;
        try {
            code = Long.parseLong(m.group(1));
        } catch (NumberFormatException tooLong) {
            return Optional.empty();   // a hand-named file no version fits
        }
        String rest = m.group(2) == null ? "" : m.group(2);
        CpuAbi abi = CpuAbi.ANY;
        int cut = rest.lastIndexOf('_');
        // x86_64 holds a '_' itself: the kind is the longest known one the name ends with
        for (CpuAbi known : List.of(CpuAbi.X86_64, CpuAbi.ARM64_V8A, CpuAbi.ARMEABI_V7A, CpuAbi.X86)) {
            if (rest.endsWith("_" + known.id())) {
                abi = known;
                cut = rest.length() - known.id().length() - 1;
                break;
            }
        }
        String name = abi == CpuAbi.ANY ? rest : rest.substring(0, Math.max(cut, 0));
        return Optional.of(new Entry(packageName, code, name.isBlank() ? null : name, abi, file));
    }

    private Path folder(String packageName) {
        return packageName != null && PACKAGE.matcher(packageName).matches() ? root.resolve(packageName) : null;
    }

    private static Path key(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException gone) {
            return absolute;
        }
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
