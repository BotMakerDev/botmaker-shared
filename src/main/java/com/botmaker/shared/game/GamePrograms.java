package com.botmaker.shared.game;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The Windows programs in a game's folder, and which of them is the game, for a library that records the folder
 * but not the program (itch) or whose program is a stand-in (Epic's bootstrapper). Blocking; never throws.
 */
final class GamePrograms {

    /** How deep a game's own program sits below its install folder: Unreal puts it at {@code <Game>/Binaries/Win64}. */
    private static final int DEPTH = 4;

    /**
     * Programs a game folder carries that are not the game, by their usual names; only {@link #largest} sets them
     * aside, so a game called "Crash…" is still found by its name.
     */
    private static final Pattern HELPER = Pattern.compile(
            "(?i)^(UnityCrashHandler|CrashReportClient|EpicOnlineServices|EpicWebHelper|UE4?PrereqSetup|unins)"
                    + "|redist|^setup|^dxsetup");

    private GamePrograms() {}

    /** Every {@code .exe} up to {@link #DEPTH} below {@code dir}, in path order; unreadable folders skipped. */
    static List<Path> under(Path dir) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            // what was found before the failure still counts
        }
        found.sort(Comparator.comparing(Path::toString));
        return found;
    }

    /**
     * The largest of {@code programs} once helpers (crash reporters, installers, redistributables) and those
     * {@code skip} names are set aside; {@code null} when none is left.
     */
    static Path largest(List<Path> programs, Predicate<Path> skip) {
        Path largest = null;
        long most = -1;
        for (Path p : programs) {
            if (HELPER.matcher(p.getFileName().toString()).find() || skip.test(p)) continue;
            long size = size(p);
            if (size > most) {
                largest = p;
                most = size;
            }
        }
        return largest;
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
