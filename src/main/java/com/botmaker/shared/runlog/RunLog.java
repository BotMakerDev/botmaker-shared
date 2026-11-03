package com.botmaker.shared.runlog;

import com.botmaker.shared.ipc.TelemetryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * One run's log on disk: {@code target/botmaker-runs/<yyyyMMdd-HHmmss>/run.jsonl} in the bot's folder, JSON Lines,
 * the {@linkplain RunFacts run's facts} first, then one trace line per line, each flushed as written so a run that
 * dies keeps every whole line before it ({@link RunLogReader} skips a torn last one). Written by the bot's own
 * process from {@code Diag}'s file sink, so it works under Studio, a run copy and the dashboard alike (spec
 * {@code docs/superpowers/specs/2026-11-03-run-reports-design.md} §2).
 *
 * <p><b>Bounded.</b> The last {@value #KEEP_RUNS} runs' folders are kept, the folder the miss frames share
 * ({@link #newFolder}). A log stops taking debug lines past {@value #DEBUG_BYTES} bytes and every line past
 * {@value #MAX_BYTES}, saying so once: new lines are dropped, old ones never rewritten.
 */
public final class RunLog implements AutoCloseable {

    /** Where runs' folders go, relative to the bot's folder. */
    public static final Path FOLDER = Path.of("target", "botmaker-runs");
    /** The log's file name in its run's folder. */
    public static final String FILE = "run.jsonl";
    public static final int KEEP_RUNS = 5;
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final long DEBUG_BYTES = 4L * 1024 * 1024;

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RUN_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path folder;
    private final BufferedWriter out;
    private long bytes;
    private boolean full;
    private boolean closed;

    private RunLog(Path folder, BufferedWriter out) {
        this.folder = folder;
        this.out = out;
    }

    /** Starts a run's log in a new folder under {@code root}, its facts as the first line. */
    public static RunLog start(Path root, RunFacts facts, Clock clock) throws IOException {
        return in(newFolder(root, clock), facts);
    }

    /**
     * Starts a run's log in {@code folder}, a run's folder {@link #newFolder} made: the one the run's miss frames
     * already use, so a run has one folder.
     */
    public static RunLog in(Path folder, RunFacts facts) throws IOException {
        Files.createDirectories(folder);
        RunLog log = new RunLog(folder, Files.newBufferedWriter(folder.resolve(FILE), StandardCharsets.UTF_8));
        ObjectNode first = MAPPER.createObjectNode();
        first.set("facts", MAPPER.valueToTree(facts));
        log.append(first.toString());
        return log;
    }

    /**
     * A new run's folder under {@code root}, named by {@code clock}'s local time, after dropping all but the newest
     * {@code KEEP_RUNS - 1} earlier ones. The run log and the miss frames share it.
     */
    public static Path newFolder(Path root, Clock clock) throws IOException {
        Files.createDirectories(root);
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> old = dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName).reversed()).toList();
            for (Path dir : old.subList(Math.min(old.size(), KEEP_RUNS - 1), old.size())) delete(dir);
        }
        String name = LocalDateTime.now(clock).format(RUN_NAME);
        Path dir = root.resolve(name);
        for (int n = 2; Files.exists(dir); n++) dir = root.resolve(name + "-" + n);
        return Files.createDirectories(dir);
    }

    /** This run's folder. */
    public Path folder() {
        return folder;
    }

    /** This run's log file. */
    public Path file() {
        return folder.resolve(FILE);
    }

    /** Appends {@code line}, unless the log is past its cap for that level. Never throws: a lost line costs itself. */
    public synchronized void write(TelemetryEvent.Log line) {
        if (closed || full) return;
        if (bytes >= MAX_BYTES) {
            full = true;
            append(json(new TelemetryEvent.Log(TelemetryEvent.Log.WARN, "RunLog",
                    "The run's log is full; later lines are not kept.", 1, line.atMillis(), null, "", "", "", -1)));
            return;
        }
        if (bytes >= DEBUG_BYTES && TelemetryEvent.Log.DEBUG.equals(line.level())) return;
        append(json(line));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            out.close();
        } catch (IOException ignored) {
            // the lines written are flushed already
        }
    }

    static String json(TelemetryEvent.Log line) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("t", line.atMillis());
        node.put("level", line.level());
        node.put("source", line.source());
        node.put("text", line.text());
        if (line.count() > 1) node.put("count", line.count());
        if (line.rect() != null) {
            node.putArray("rect").add(line.rect().x()).add(line.rect().y())
                    .add(line.rect().width()).add(line.rect().height());
        }
        if (!line.writerClass().isEmpty()) node.put("writerClass", line.writerClass());
        if (!line.writerMethod().isEmpty()) node.put("writerMethod", line.writerMethod());
        if (!line.className().isEmpty()) node.put("class", line.className());
        if (line.line() >= 0) node.put("line", line.line());
        return node.toString();
    }

    private void append(String json) {
        try {
            out.write(json);
            out.write('\n');
            out.flush();
            bytes += json.getBytes(StandardCharsets.UTF_8).length + 1L;
        } catch (IOException ignored) {
            // a disk that refuses a line loses that line, never the run
        }
    }

    /** Deletes {@code dir} and everything under it, deepest first. */
    private static void delete(Path dir) throws IOException {
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
