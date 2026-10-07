package com.botmaker.shared.emulator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An Android app as a file on this computer, ready to install: a plain {@code .apk}, or an {@code .xapk} /
 * {@code .apks} archive of split APKs (and, in an {@code .xapk}, the game's {@code Android/obb} data). The
 * archive's APKs are extracted to a temporary folder, which {@link #close()} removes.
 *
 * <p>Its package and name are read out of the APKs themselves ({@link ApkLabel}): they are what the installed
 * app is picked by afterwards, and an archive's own {@code manifest.json} is only the fallback.
 *
 * @param packageName the app's package, e.g. {@code com.supercell.clashofclans}
 * @param label       its name as the launcher shows it, or {@code null}
 * @param apks        the APK files to install together: one for a plain {@code .apk}, the base and its splits
 * @param obbs        expansion files to copy to the device after the install
 * @param extracted   the temporary folder an archive was extracted to, or {@code null} for a plain {@code .apk}
 */
public record ApkFile(String packageName, String label, List<Path> apks, List<Obb> obbs, Path extracted)
        implements AutoCloseable {

    /** One expansion file: where it was extracted, and where on the device it goes. */
    public record Obb(Path local, String devicePath) {}

    private static final Pattern JSON_PACKAGE = Pattern.compile("\"package_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final String OBB_PREFIX = "Android/obb/";

    public ApkFile {
        apks = List.copyOf(apks);
        obbs = List.copyOf(obbs);
    }

    /**
     * Reads {@code file}. Throws with a sentence a UI can show when it isn't an Android app this can install.
     */
    public static ApkFile open(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) throw new IOException("There is no file at " + file + ".");
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk")) {
            ApkLabel.ApkFacts facts = facts(file);
            if (facts == null) throw new IOException(file.getFileName() + " isn't an Android app (no manifest in it).");
            return new ApkFile(facts.packageName(), facts.label(), List.of(file), List.of(), null);
        }
        if (name.endsWith(".xapk") || name.endsWith(".apks")) return openArchive(file);
        throw new IOException(file.getFileName() + " isn't an .apk, .xapk or .apks file.");
    }

    private static ApkFile openArchive(Path file) throws IOException {
        Path folder = Files.createTempDirectory("botmaker-apk-");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<Path> apks = new ArrayList<>();
            List<Obb> obbs = new ArrayList<>();
            String manifestJson = null;
            int n = 0;
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName().replace('\\', '/');
                // bundletool's standalones/ are whole-app alternatives to the splits, not more of them: installed
                // together with the splits, the session has two base APKs and Android refuses it.
                if (entryName.startsWith("standalones/")) continue;
                if (entryName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    // Numbered, not named after the entry: the archive's paths never reach this computer's disk.
                    Path apk = folder.resolve((n++) + ".apk");
                    copy(zip, entry, apk);
                    apks.add(apk);
                } else if (entryName.startsWith(OBB_PREFIX) && safe(entryName)) {
                    Path obb = folder.resolve("obb-" + (n++));
                    copy(zip, entry, obb);
                    obbs.add(new Obb(obb, "/sdcard/" + entryName));
                } else if (entryName.equals("manifest.json")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        manifestJson = new String(in.readNBytes(1 << 20), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            if (apks.isEmpty()) throw new IOException(file.getFileName() + " holds no APK.");
            String pkg = null;
            String label = null;
            for (Path apk : apks) {
                ApkLabel.ApkFacts facts = facts(apk);
                if (facts == null) continue;
                if (pkg == null) pkg = facts.packageName();
                if (label == null) label = facts.label();
            }
            if (pkg == null) pkg = group(JSON_PACKAGE, manifestJson);
            if (label == null) label = group(JSON_NAME, manifestJson);
            if (pkg == null) throw new IOException(file.getFileName() + " doesn't say which app it is.");
            return new ApkFile(pkg, label, apks, obbs, folder);
        } catch (IOException | RuntimeException e) {
            delete(folder);
            throw e instanceof IOException io ? io : new IOException(file.getFileName() + " can't be read: "
                    + e.getMessage(), e);
        }
    }

    /** An entry path that stays where it says: no {@code ..}, no absolute path. */
    static boolean safe(String entryName) {
        if (entryName.startsWith("/")) return false;
        for (String segment : entryName.split("/")) {
            if (segment.equals("..") || segment.isEmpty()) return false;
        }
        return true;
    }

    /** The app's name, else its package. */
    public String display() {
        return label == null || label.isBlank() ? packageName : label;
    }

    /** All the files to send, in bytes. */
    public long size() {
        long total = 0;
        for (Path apk : apks) total += sizeOf(apk);
        for (Obb obb : obbs) total += sizeOf(obb.local());
        return total;
    }

    /** Removes what was extracted; a plain {@code .apk} extracted nothing. */
    @Override
    public void close() {
        if (extracted != null) delete(extracted);
    }

    private static ApkLabel.ApkFacts facts(Path apk) {
        try (FileChannel channel = FileChannel.open(apk, StandardOpenOption.READ)) {
            return ApkLabel.facts(reader(channel));
        } catch (IOException e) {
            return null;
        }
    }

    /** Ranged reads of a file on this computer: the same {@link ApkZip.Reader} the device's APKs are read through. */
    private static ApkZip.Reader reader(FileChannel channel) {
        return new ApkZip.Reader() {
            @Override
            public long size() {
                try {
                    return channel.size();
                } catch (IOException e) {
                    return -1;
                }
            }

            @Override
            public byte[] read(long offset, int length) {
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(length);
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer, offset + buffer.position()) < 0) break;
                    }
                    return java.util.Arrays.copyOf(buffer.array(), buffer.position());
                } catch (IOException e) {
                    return new byte[0];
                }
            }
        };
    }

    private static void copy(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String group(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static void delete(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // a leftover in the temp folder is not worth failing an install over
                }
            });
        } catch (IOException ignored) {
            // as above
        }
    }
}
