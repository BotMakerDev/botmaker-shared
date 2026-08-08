package com.botmaker.shared.tools;

import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <b>Unpacking a downloaded archive, safely and usably.</b> Two things a naive extraction gets wrong, and
 * both of them matter for the one archive this exists for (Google's {@code platform-tools} zip):
 *
 * <ul>
 *   <li><b>Zip slip.</b> An entry named {@code ../../.bashrc} extracts <em>outside</em> the target directory
 *       if the path is simply resolved. Every entry here is resolved and then checked to still be under the
 *       target, and one that isn't fails the whole extraction rather than being skipped — an archive
 *       containing such an entry is not an archive to take the rest of on trust.</li>
 *   <li><b>The executable bit.</b> A {@code ZipEntry} carries no POSIX mode through
 *       {@link ZipInputStream}, so a freshly extracted {@code adb} is not executable and every later
 *       "no adb found" is really "adb is not runnable". See {@link #executable} for the rule used.</li>
 * </ul>
 *
 * <p>Like everything else in this package it returns a boolean rather than throwing: a failed extraction means
 * the tool is not installed, which is a state the callers already handle.
 */
public final class Unzip {

    private Unzip() {}

    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * Extracts {@code zip} into {@code targetDir}, creating it if needed, overwriting existing files.
     *
     * <p>Entries keep their own paths — the platform-tools archive has a single {@code platform-tools/} root,
     * so extracting into {@code tools/} produces {@code tools/platform-tools/adb}, which is where
     * {@link ManagedTools} then looks. Nothing is stripped, because stripping a root only some archives have
     * is a rule that silently does the wrong thing to the next archive.
     */
    public static boolean extract(Path zip, Path targetDir) {
        if (zip == null || targetDir == null || !Files.isRegularFile(zip)) {
            return false;
        }
        try {
            Files.createDirectories(targetDir);
            Path root = targetDir.toRealPath();
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    Path destination = resolve(root, entry.getName());
                    if (destination == null) {
                        return false;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                        continue;
                    }
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                    if (executable(destination)) {
                        makeExecutable(destination);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The entry's destination, or {@code null} if it would land outside {@code root}.
     *
     * <p>Package-visible and pure so the traversal cases — {@code ../x}, an absolute path, a Windows
     * {@code ..\x} — are asserted without writing an archive to disk for each.
     */
    static Path resolve(Path root, String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return null;
        }
        // Normalise the separator first: a zip written on Windows may use backslashes, which are an ordinary
        // filename character on Linux — so "..\\evil" would resolve to one harmless-looking child name here
        // and to a parent directory on the machine that wrote it.
        String name = entryName.replace('\\', '/');
        Path destination = root.resolve(name).normalize();
        return destination.startsWith(root) && !destination.equals(root) ? destination : null;
    }

    /**
     * Whether an extracted file should be made executable: a regular file with <b>no extension</b>, directly
     * inside the archive's own directory.
     *
     * <p>That rule is exactly right for platform-tools — {@code adb}, {@code fastboot}, {@code etc1tool},
     * {@code hprof-conv}, {@code sqlite3}, {@code mke2fs} have no extension; {@code adb.exe} and the
     * {@code .dll}/{@code .so} files do not need the bit — and it is deliberately not a hard-coded name list,
     * which would need editing every time Google adds a tool.
     */
    static boolean executable(Path file) {
        String name = file.getFileName().toString();
        return !name.contains(".");
    }

    private static void makeExecutable(Path file) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(file));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (Exception ignored) {
            // Best effort: an un-executable adb surfaces as "no adb found", which is already a handled state.
        }
    }
}
