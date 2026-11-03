package com.botmaker.shared.runlog;

import com.botmaker.shared.ipc.TelemetryEvent;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a {@link RunLog}'s file back. A line that is not a whole JSON object — the last one of a run that died
 * mid-write — is skipped, never an error: the lines before it are the run's record.
 */
public final class RunLogReader {

    private RunLogReader() {}

    /** The trace lines of {@code file}, in order, without its facts; empty when it cannot be read. */
    public static List<TelemetryEvent.Log> read(Path file) {
        List<TelemetryEvent.Log> lines = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String text = in.readLine(); text != null; text = in.readLine()) {
                JsonNode node = parse(text);
                if (node != null && !node.has("facts")) lines.add(line(node));
            }
        } catch (IOException ignored) {
            // what was read stands
        }
        return lines;
    }

    /** The facts on {@code file}'s first line, or empty when it has none. */
    public static Optional<RunFacts> facts(Path file) {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonNode node = parse(in.readLine());
            if (node == null || !node.has("facts")) return Optional.empty();
            return Optional.of(RunLog.MAPPER.treeToValue(node.get("facts"), RunFacts.class));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode node = RunLog.MAPPER.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static TelemetryEvent.Log line(JsonNode node) {
        JsonNode r = node.get("rect");
        TelemetryEvent.Rect rect = r != null && r.isArray() && r.size() == 4
                ? new TelemetryEvent.Rect(r.get(0).asInt(), r.get(1).asInt(), r.get(2).asInt(), r.get(3).asInt())
                : null;
        return new TelemetryEvent.Log(node.path("level").asText(), node.path("source").asText(),
                node.path("text").asText(), node.path("count").asInt(1), node.path("t").asLong(), rect,
                node.path("writerClass").asText(), node.path("writerMethod").asText(),
                node.path("class").asText(), node.path("line").asInt(-1));
    }
}
