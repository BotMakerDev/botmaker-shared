package com.botmaker.shared.emulator.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedFiltersTest {

    @Test
    void savesReplacesByNameWithinATabAndRemoves(@TempDir Path dir) throws IOException {
        SavedFilters filters = new SavedFilters(dir);
        SavedFilters.SavedFilter farm = new SavedFilters.SavedFilter("Farm needing work", SavedFilters.Tab.INSTANCES,
                Map.of("tag", "farm", "differs", "true"));

        filters.save(farm);
        filters.save(new SavedFilters.SavedFilter("Farm needing work", SavedFilters.Tab.RUNNING, Map.of()));
        filters.save(new SavedFilters.SavedFilter("Farm needing work", SavedFilters.Tab.INSTANCES, Map.of("tag", "farm")));

        List<SavedFilters.SavedFilter> saved = filters.load();
        assertEquals(2, saved.size());
        assertEquals(Map.of("tag", "farm"), saved.getFirst().values());

        filters.remove(SavedFilters.Tab.INSTANCES, "Farm needing work");
        assertEquals(List.of(SavedFilters.Tab.RUNNING), filters.load().stream().map(SavedFilters.SavedFilter::tab).toList());
    }

    @Test
    void savingRemovesDuplicatesOfTheName(@TempDir Path dir) throws IOException {
        SavedFilters filters = new SavedFilters(dir);
        Files.writeString(filters.file(), """
                {"v": 1, "filters": [{"name": "farm", "tab": "instances", "values": {"a": "1"}},
                                     {"name": "farm", "tab": "instances", "values": {"a": "2"}}]}
                """, StandardCharsets.UTF_8);

        filters.save(new SavedFilters.SavedFilter("farm", SavedFilters.Tab.INSTANCES, Map.of("a", "3")));

        assertEquals(List.of(Map.of("a", "3")), filters.load().stream().map(SavedFilters.SavedFilter::values).toList());
    }

    @Test
    void anUnknownTabsFilterIsKept(@TempDir Path dir) throws IOException {
        SavedFilters filters = new SavedFilters(dir);
        Files.writeString(filters.file(), """
                {"v": 1, "filters": [{"name": "Later", "tab": "accounts", "values": {}}]}
                """, StandardCharsets.UTF_8);

        filters.save(new SavedFilters.SavedFilter("Mine", SavedFilters.Tab.EMULATORS, Map.of()));

        assertEquals(1, filters.load().size());
        assertTrue(Files.readString(filters.file(), StandardCharsets.UTF_8).contains("\"accounts\""));
    }
}
