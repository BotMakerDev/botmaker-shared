package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The settings files a Windows emulator Fix changed, kept before it changed them, so Undo puts one back. One folder
 * per instance under {@code <config>/emulator-backups/}, one per Fix in it, named {@code <stamp>-<check>}: the copy,
 * and {@code origin.txt} saying where it came from. An undone one is renamed {@code …-undone}, as {@link Backups}
 * does for the translator, so the newest one still named by a time and a check is what the next Undo restores.
 */
public final class WindowsBackups {

    static final String ORIGIN = "origin.txt";

    private WindowsBackups() {}

    /** One kept file: its folder, the file's copy in it and where it goes back to. */
    public record Backup(Path folder, Path copy, Path origin, EmulatorCheck check) {}

    static Path root() {
        return UserDirs.config().resolve("emulator-backups");
    }

    /** Copies {@code file} into a new backup of {@code check}'s Fix on {@code instance}. */
    static Backup save(Path root, EmulatorInstance instance, EmulatorCheck check, Path file, LocalDateTime when)
            throws IOException {
        Path folder = root.resolve(folderOf(instance)).resolve(Backups.stamp(when) + "-" + check.id());
        Files.createDirectories(folder);
        Path copy = folder.resolve(file.getFileName().toString());
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(folder.resolve(ORIGIN), file.toAbsolutePath().toString());
        return new Backup(folder, copy, file.toAbsolutePath(), check);
    }

    static Backup save(EmulatorInstance instance, EmulatorCheck check, Path file) throws IOException {
        return save(root(), instance, check, file, LocalDateTime.now());
    }

    /** The backup the next Undo of {@code check} on {@code instance} restores; empty when none is left. */
    public static Optional<Backup> newest(EmulatorInstance instance, EmulatorCheck check) {
        return newest(root(), instance, check);
    }

    static Optional<Backup> newest(Path root, EmulatorInstance instance, EmulatorCheck check) {
        Pattern name = Pattern.compile("\\d{8}-\\d{6}-" + Pattern.quote(check.id()));
        try (Stream<Path> entries = Files.list(root.resolve(folderOf(instance)))) {
            return entries.filter(Files::isDirectory).filter(p -> name.matcher(p.getFileName().toString()).matches())
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .flatMap(folder -> read(folder, check));
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static Optional<Backup> read(Path folder, EmulatorCheck check) {
        try (Stream<Path> files = Files.list(folder)) {
            Path origin = Path.of(Files.readString(folder.resolve(ORIGIN)).trim());
            return files.filter(p -> !p.getFileName().toString().equals(ORIGIN)).findFirst()
                    .map(copy -> new Backup(folder, copy, origin, check));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Marks {@code backup} restored, so the next Undo takes the one before it. */
    static void undone(Backup backup) throws IOException {
        Files.move(backup.folder(), backup.folder().resolveSibling(backup.folder().getFileName() + "-undone"));
    }

    /** {@code ldplayer-My_Player}: the product and the instance's name, safe as a folder name. */
    static String folderOf(EmulatorInstance instance) {
        return instance.platformId().id() + "-" + instance.name().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
