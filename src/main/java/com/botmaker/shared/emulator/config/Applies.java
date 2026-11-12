package com.botmaker.shared.emulator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The applies log, {@code applies.jsonl}: one line per Apply ({@code {"v":1,"id","at","instance","changes":[{field,
 * before,after}]}}) and one per Undo ({@code {"undone":id}}). Lines are only appended, so two writers never lose one;
 * a line this build cannot read is skipped. Values are written as the configuration writes them.
 */
public final class Applies {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One Apply logged. */
    public record Entry(String id, Instant at, InstanceKey instance, List<Apply.Change> changes) {
        public Entry {
            changes = List.copyOf(changes);
        }

        /** The changes Undo makes: each the other way round, but one whose old value was never read. */
        public List<Apply.Change> reversed() {
            return changes.stream().filter(c -> c.before() != null)
                    .map(c -> new Apply.Change(c.field(), c.after(), c.before())).toList();
        }
    }

    private final Path file;
    private final Clock clock;

    public Applies(Path file) {
        this(file, Clock.systemUTC());
    }

    Applies(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Logs an Apply of {@code changes} and answers its id; nothing logged, null, when there were none. */
    public String record(InstanceKey instance, List<Apply.Change> changes) throws IOException {
        if (changes.isEmpty()) return null;
        String id = UUID.randomUUID().toString();
        ObjectNode line = JSON.createObjectNode().put("v", 1).put("id", id).put("at", clock.instant().toString())
                .put("instance", instance.text());
        ArrayNode list = line.putArray("changes");
        for (Apply.Change change : changes) {
            ObjectNode node = list.addObject().put("field", change.field().id());
            if (change.before() != null) node.set("before", ConfigJson.json(change.before(), node));
            node.set("after", ConfigJson.json(change.after(), node));
        }
        append(line);
        return id;
    }

    /** Marks {@code id} undone, so {@link #last} goes to the Apply before it. */
    public void undone(String id) throws IOException {
        append(JSON.createObjectNode().put("undone", id));
    }

    /** The newest Apply on {@code instance} not undone. */
    public Optional<Entry> last(InstanceKey instance) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> undone = new HashSet<>();
        for (JsonNode line : lines()) {
            if (line.hasNonNull("undone")) {
                undone.add(line.get("undone").asText());
            } else {
                Entry entry = entry(line);
                if (entry != null && entry.instance().equals(instance)) entries.add(entry);
            }
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!undone.contains(entries.get(i).id())) return Optional.of(entries.get(i));
        }
        return Optional.empty();
    }

    /** The ids of every Apply undone, on any instance. */
    public Set<String> undoneIds() throws IOException {
        Set<String> undone = new HashSet<>();
        for (JsonNode line : lines()) {
            if (line.hasNonNull("undone")) undone.add(line.get("undone").asText());
        }
        return undone;
    }

    /** Every Apply on {@code instance}, oldest first, undone ones included ({@link #undoneIds}); the window's history. */
    public List<Entry> all(InstanceKey instance) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (JsonNode line : lines()) {
            Entry entry = line.hasNonNull("undone") ? null : entry(line);
            if (entry != null && entry.instance().equals(instance)) out.add(entry);
        }
        return out;
    }

    private static Entry entry(JsonNode line) {
        if (line.path("v").asInt() != 1 || !line.hasNonNull("id")) return null;
        InstanceKey key = InstanceKey.parse(line.path("instance").asText(""));
        if (key == null) return null;
        Instant at;
        try {
            at = Instant.parse(line.path("at").asText());
        } catch (RuntimeException e) {
            return null;
        }
        List<Apply.Change> changes = new ArrayList<>();
        for (JsonNode node : line.path("changes")) {
            ConfigField field = ConfigField.fromId(node.path("field").asText());
            ConfigValue after = ConfigJson.value(field, node.path("after"));
            if (field == ConfigField.UNKNOWN || after == null) continue;
            ConfigValue before = node.has("before") ? ConfigJson.value(field, node.get("before")) : null;
            changes.add(new Apply.Change(field, before, after));
        }
        return new Entry(line.get("id").asText(), at, key, changes);
    }

    private List<JsonNode> lines() throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<JsonNode> out = new ArrayList<>();
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (text.isBlank()) continue;
            try {
                JsonNode node = JSON.readTree(text);
                if (node != null && node.isObject()) out.add(node);
            } catch (IOException e) {
                // a line cut short or written by a newer build: not this build's
            }
        }
        return out;
    }

    /**
     * A file lock is the whole JVM's: a second lock from this process throws instead of waiting, so every
     * {@code Applies} on one file takes its path's monitor first, and the file lock only keeps other processes out.
     */
    private static final Map<Path, Object> IN_PROCESS = new ConcurrentHashMap<>();

    private void append(ObjectNode line) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        byte[] bytes = (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (IN_PROCESS.computeIfAbsent(path, p -> new Object())) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
                 FileLock ignored = channel.lock()) {
                channel.write(ByteBuffer.wrap(bytes));
            }
        }
    }
}
