package com.botmaker.shared.reports;

import com.botmaker.runlog.Scrubber;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * <i>Export logs</i> (run reports spec §3), for a bot with no repository to report to: a run's folder as a zip of
 * its text files, each scrubbed as an issue would be. Pictures and anything else that is not text stay behind.
 */
public final class ExportLogs {

    private static final List<String> TEXT = List.of(".jsonl", ".json", ".txt", ".log");

    private ExportLogs() {
    }

    /** Writes {@code runFolder}'s text files, scrubbed, to the zip {@code out}; the names written. */
    public static List<String> zip(Path runFolder, Path out, Scrubber scrubber) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(runFolder)) {
            files = list.filter(Files::isRegularFile)
                    .filter(f -> TEXT.stream().anyMatch(f.getFileName().toString().toLowerCase(Locale.ROOT)::endsWith))
                    .sorted().toList();
        }
        List<String> names = new ArrayList<>();
        try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Path f : files) {
                String name = runFolder.getFileName() + "/" + f.getFileName();
                zip.putNextEntry(new ZipEntry(name));
                StringBuilder text = new StringBuilder();
                // Decoded leniently: one torn byte must not stop the export.
                for (String line : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).lines().toList()) {
                    text.append(scrubber.scrub(line)).append('\n');
                }
                zip.write(text.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                names.add(name);
            }
        }
        return names;
    }
}
