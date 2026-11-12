package com.botmaker.shared.emulator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Emulators window's saved filters ({@code filters.json} in the folder the window's owner gives): a name, the tab it
 * belongs to, and the filter's values by the window's own keys. An entry of a tab this build does not know is kept.
 */
public final class SavedFilters {

    private final Path dir;

    public SavedFilters(Path dir) {
        this.dir = dir;
    }

    public Path file() {
        return dir.resolve("filters.json");
    }

    /** The window's tabs a filter belongs to. */
    public enum Tab {
        EMULATORS("emulators", "Emulators"),
        INSTANCES("instances", "Instances"),
        CONFIGURATIONS("configurations", "Configurations"),
        RUNNING("running", "Running"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Tab(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Tab fromId(String id) {
            for (Tab tab : values()) {
                if (tab.id.equals(id)) {
                    return tab;
                }
            }
            return UNKNOWN;
        }
    }

    /** One saved filter; its name is unique within its tab. */
    public record SavedFilter(String name, Tab tab, Map<String, String> values) {
        public SavedFilter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("a saved filter has a name");
            }
            if (tab == null || tab == Tab.UNKNOWN) {
                throw new IllegalArgumentException("a saved filter belongs to a tab");
            }
            name = name.strip();
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /** The saved filters of every tab, in the order they were saved. */
    public List<SavedFilter> load() throws IOException {
        return read(JsonFiles.read(file()));
    }

    /** Saves the filter, replacing the one of its name in its tab. */
    public List<SavedFilter> save(SavedFilter filter) throws IOException {
        return JsonFiles.change(file(), root -> {
            ArrayNode list = list(root);
            ObjectNode entry = null;
            for (int i = 0; i < list.size(); i++) {
                if (!same(list.get(i), filter.tab(), filter.name())) {
                    continue;
                }
                if (entry == null && list.get(i) instanceof ObjectNode object) {
                    entry = object;
                } else {
                    list.remove(i--);
                }
            }
            if (entry == null) {
                entry = list.addObject();
            }
            entry.put("name", filter.name());
            entry.put("tab", filter.tab().id());
            ObjectNode values = entry.putObject("values");
            filter.values().forEach(values::put);
            return read(root);
        });
    }

    public List<SavedFilter> remove(Tab tab, String name) throws IOException {
        return JsonFiles.change(file(), root -> {
            ArrayNode list = list(root);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (same(list.get(i), tab, name)) {
                    list.remove(i);
                }
            }
            return read(root);
        });
    }

    private static List<SavedFilter> read(ObjectNode root) {
        List<SavedFilter> out = new ArrayList<>();
        for (JsonNode entry : root.path("filters")) {
            Tab tab = Tab.fromId(entry.path("tab").asText());
            String name = entry.path("name").asText("");
            if (tab == Tab.UNKNOWN || name.isBlank()) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            entry.path("values").properties().forEach(value -> {
                if (value.getValue().isTextual()) {
                    values.put(value.getKey(), value.getValue().asText());
                }
            });
            out.add(new SavedFilter(name, tab, values));
        }
        return out;
    }

    private static ArrayNode list(ObjectNode root) {
        root.put("v", Math.max(1, root.path("v").asInt(1)));
        JsonNode list = root.get("filters");
        return list instanceof ArrayNode array ? array : root.putArray("filters");
    }

    private static boolean same(JsonNode entry, Tab tab, String name) {
        return tab.id().equals(entry.path("tab").asText()) && name.strip().equals(entry.path("name").asText());
    }
}
