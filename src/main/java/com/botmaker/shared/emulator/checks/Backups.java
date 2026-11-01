package com.botmaker.shared.emulator.checks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The translator backups the root scripts leave under {@link TranslatorScripts#BACKUPS}, one folder per Fix named by
 * its time. An undone one is renamed {@code <stamp>-undone}, so the newest one still named by a time alone is what
 * the next Undo restores. The folders are root's, but anyone may list them.
 */
public final class Backups {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Backups() {}

    /** The folder name of a backup made at {@code when}. */
    static String stamp(LocalDateTime when) {
        return STAMP.format(when);
    }

    /** The backup the next Undo restores; empty when there is none. */
    public static Optional<Path> newest() {
        return newest(Path.of(TranslatorScripts.BACKUPS));
    }

    static Optional<Path> newest(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().matches("\\d{8}-\\d{6}"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()));
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }
}
