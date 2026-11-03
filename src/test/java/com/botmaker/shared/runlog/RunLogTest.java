package com.botmaker.shared.runlog;

import com.botmaker.shared.ipc.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run's log: its facts first, one JSON object per line, five runs kept, a cap, a torn last line skipped. */
class RunLogTest {

    @TempDir
    Path root;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T10:15:30Z"), ZoneId.of("UTC"));

    private static RunFacts facts() {
        return new RunFacts("com.example:bot", "v1.2.0", "abc1234", false, Map.of("botmaker-sdk", "2.0.0"),
                "Linux 7.2", 1L, Map.of("Flow", "Collect", "Game version", "1.4.2 (142)"));
    }

    private static TelemetryEvent.Log line(String level, String text) {
        return new TelemetryEvent.Log(level, "Vision", text, 2, 5L, new TelemetryEvent.Rect(1, 2, 3, 4),
                "com.botmaker.sdk.api.vision.ImageFinder", "find", "com.example.Collect", 42);
    }

    @Test
    void factsAreTheFirstLineAndEachLineReadsBack() throws IOException {
        try (RunLog log = RunLog.start(root, facts(), CLOCK)) {
            log.write(line(TelemetryEvent.Log.DEBUG, "find(ORE) → miss"));
            log.write(line(TelemetryEvent.Log.WARN, "stuck on Home"));
            assertEquals("20261103-101530", log.folder().getFileName().toString());
        }
        Path file = root.resolve("20261103-101530").resolve(RunLog.FILE);
        List<String> raw = Files.readAllLines(file);
        assertEquals(3, raw.size());
        assertTrue(raw.getFirst().startsWith("{\"facts\":"), raw.getFirst());
        assertEquals(facts(), RunLogReader.facts(file).orElseThrow());
        List<TelemetryEvent.Log> lines = RunLogReader.read(file);
        assertEquals(List.of(line(TelemetryEvent.Log.DEBUG, "find(ORE) → miss"),
                line(TelemetryEvent.Log.WARN, "stuck on Home")), lines);
    }

    @Test
    void tornLastLineIsSkipped() throws IOException {
        Path file;
        try (RunLog log = RunLog.start(root, facts(), CLOCK)) {
            log.write(line(TelemetryEvent.Log.INFO, "whole"));
            file = log.file();
        }
        Files.writeString(file, "{\"t\":9,\"level\":\"err", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        List<TelemetryEvent.Log> lines = RunLogReader.read(file);
        assertEquals(1, lines.size());
        assertEquals("whole", lines.getFirst().text());
    }

    @Test
    void fiveRunsAreKept() throws IOException {
        for (int i = 0; i < 7; i++) RunLog.start(root, facts(), CLOCK).close();
        try (Stream<Path> dirs = Files.list(root)) {
            assertEquals(RunLog.KEEP_RUNS, dirs.count());
        }
    }

    @Test
    void anOldRunWithASubfolderIsStillPruned() throws IOException {
        for (int i = 0; i < RunLog.KEEP_RUNS; i++) {
            Path folder = RunLog.newFolder(root, CLOCK);
            Files.createDirectories(folder.resolve("frames").resolve("deep"));
        }
        RunLog.start(root, facts(), CLOCK).close();
        try (Stream<Path> dirs = Files.list(root)) {
            assertEquals(RunLog.KEEP_RUNS, dirs.count());
        }
    }

    @Test
    void aFactUnknownToThePluginReadsUnknown() {
        Map<String, String> details = new HashMap<>();
        details.put("Game version", null);
        details.put(null, "x");
        RunFacts facts = new RunFacts(null, null, null, false, null, null, 0, details);
        assertEquals(Map.of("Game version", RunFacts.UNKNOWN), facts.details());
        assertEquals(RunFacts.UNKNOWN, facts.bot());
    }

    @Test
    void aLogStartedInAnExistingFolderSharesIt() throws IOException {
        Path folder = RunLog.newFolder(root, CLOCK);
        try (RunLog log = RunLog.in(folder, facts())) {
            assertEquals(folder, log.folder());
        }
        try (Stream<Path> dirs = Files.list(root)) {
            assertEquals(1, dirs.count());
        }
    }

    @Test
    void pastItsCapTheLogDropsNewDebugLinesThenEverything() throws IOException {
        String big = "x".repeat(TelemetryEvent.Log.MAX_TEXT - 1);
        Path file;
        try (RunLog log = RunLog.start(root, facts(), CLOCK)) {
            file = log.file();
            while (Files.size(file) < RunLog.DEBUG_BYTES) log.write(line(TelemetryEvent.Log.DEBUG, big));
            long atDebugCap = Files.size(file);
            log.write(line(TelemetryEvent.Log.DEBUG, big));
            assertEquals(atDebugCap, Files.size(file), "a debug line past 4 MB is dropped");
            while (Files.size(file) < RunLog.MAX_BYTES) log.write(line(TelemetryEvent.Log.WARN, big));
            log.write(line(TelemetryEvent.Log.ERROR, "late"));
            long full = Files.size(file);
            log.write(line(TelemetryEvent.Log.ERROR, "later"));
            assertEquals(full, Files.size(file));
        }
        List<TelemetryEvent.Log> lines = RunLogReader.read(file);
        assertEquals("The run's log is full; later lines are not kept.", lines.getLast().text());
        assertTrue(lines.stream().noneMatch(l -> l.text().startsWith("late")));
    }
}
