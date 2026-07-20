package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The directory walk every multi-instance product's discovery shares: list a scan directory in name order,
 * ask the product what (if anything) each entry describes, and collect the answers. LDPlayer, MEmu and MuMu
 * each keep their instances as one file or folder per instance under the install dir, so only the per-entry
 * parsing genuinely differs — that is the lambda; everything around it lives here.
 *
 * <p>Guarantees the discovery contract for all of them: <b>never throws</b>. A missing directory yields an
 * empty list, and one unreadable or malformed entry is skipped rather than sinking the whole scan, so a
 * single corrupt instance config can't hide the working instances beside it.
 */
final class PlatformScan {

    private PlatformScan() {}

    /**
     * Every instance {@code perEntry} recognises among {@code scanDir}'s entries, in name order.
     *
     * @param scanDir  the directory holding one entry per instance; {@code null} or missing yields empty
     * @param perEntry parses one entry into an instance, or {@link Optional#empty()} to skip it (not an
     *                 instance entry); may throw, which is caught and treated as a skip
     */
    static List<EmulatorInstance> directory(Path scanDir, EntryParser perEntry) {
        if (scanDir == null || !Files.isDirectory(scanDir)) {
            return List.of();
        }
        List<EmulatorInstance> instances = new ArrayList<>();
        try (Stream<Path> entries = Files.list(scanDir)) {
            for (Path entry : (Iterable<Path>) entries.sorted()::iterator) {
                try {
                    perEntry.parse(entry).ifPresent(instances::add);
                } catch (Exception ignored) {
                    // skip an unreadable or malformed entry; keep discovering the rest
                }
            }
        } catch (Exception e) {
            return instances; // partial results beat none
        }
        return instances;
    }

    /** Turns one directory entry into an instance, or empty when the entry isn't one. */
    @FunctionalInterface
    interface EntryParser {
        Optional<EmulatorInstance> parse(Path entry) throws Exception;
    }
}
