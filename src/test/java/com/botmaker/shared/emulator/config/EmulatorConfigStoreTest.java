package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.PlatformId;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorConfigStoreTest {

    private static final InstanceKey LD1 = new InstanceKey(PlatformId.LDPLAYER, "LDPlayer-1");

    @Test
    void noFileIsAnEmptyConfiguration(@TempDir Path dir) throws IOException {
        assertEquals(EmulatorConfig.EMPTY, new EmulatorConfigStore(dir).load());
    }

    @Test
    void roundTripsEveryPartOfALayer(@TempDir Path dir) throws IOException {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        Layer emulator = Layer.EMPTY.with(ConfigField.SCREEN, new ConfigValue.Screen(1080, 1920, 440))
                .with(ConfigField.MEMORY, new ConfigValue.Megabytes(3072))
                .with(ConfigField.ADB, new ConfigValue.Toggle(true))
                .with(ConfigField.LOCALE, new ConfigValue.Text("en-US"))
                .with(ConfigField.PRODUCT_AUTO_UPDATE_OFF, new ConfigValue.Toggle(true))
                .withAccount(new AccountRecord(AccountRecord.Kind.GOOGLE, "pc@example.com"))
                .withGame(new GameRecord("com.zjcs.android.us", "Sword x Staff"));
        Layer instance = Layer.EMPTY.with(ConfigField.FPS, new ConfigValue.Count(15))
                .withAccount(new AccountRecord(AccountRecord.Kind.MAIL, "me@example.com", "farm mail"))
                .removingAccount(new AccountRecord.Key(AccountRecord.Kind.GOOGLE, "pc@example.com"))
                .withDefaultGoogle("other@example.com").removingGame("com.other").withTags(List.of("farm"));
        EmulatorConfig config = EmulatorConfig.EMPTY.withEmulator(PlatformId.LDPLAYER, emulator)
                .withInstance(LD1, instance);

        store.update(c -> config);

        assertEquals(config, store.load());
        assertEquals(config, new EmulatorConfigStore(dir).load());
    }

    @Test
    void keepsUnknown(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("configurations.json");
        Files.writeString(file, """
                {"v": 3, "future": {"x": 1},
                 "emulators": {
                   "ldplayer": {"fields": {"screen": {"w": 1080, "h": 1920, "dpi": 440}, "hdr": true,
                                           "memory": "lots"},
                                "accounts": [{"kind": "google", "name": "pc@example.com", "since": "2027"},
                                             {"kind": "discord", "name": "me#1"}],
                                "removedAccounts": [{"kind": "discord", "name": "old#2"}],
                                "colour": "blue"},
                   "nox": {"fields": {"fps": 60}}},
                 "instances": {"nox/Nox-1": {"tags": ["x"]}}}
                """, StandardCharsets.UTF_8);
        EmulatorConfigStore store = new EmulatorConfigStore(dir);

        EmulatorConfig read = store.load();
        assertEquals(new ConfigValue.Screen(1080, 1920, 440),
                read.emulator(PlatformId.LDPLAYER).fields().get(ConfigField.SCREEN));
        assertEquals(1, read.emulator(PlatformId.LDPLAYER).accounts().size());

        store.update(c -> c.withEmulator(PlatformId.LDPLAYER, c.emulator(PlatformId.LDPLAYER)
                .with(ConfigField.FPS, new ConfigValue.Count(30))));

        JsonNode root = JsonFiles.read(file);
        assertEquals(3, root.path("v").asInt(), "a newer file's version stays");
        assertEquals(1, root.path("future").path("x").asInt());
        JsonNode ld = root.path("emulators").path("ldplayer");
        assertTrue(ld.path("fields").path("hdr").asBoolean(), "an unknown field id stays");
        assertEquals("lots", ld.path("fields").path("memory").asText(), "a value this build cannot read stays");
        assertEquals(30, ld.path("fields").path("fps").asInt());
        assertEquals("blue", ld.path("colour").asText());
        assertEquals("2027", ld.path("accounts").get(0).path("since").asText(), "an account's unknown key stays");
        assertEquals("discord", ld.path("accounts").get(1).path("kind").asText(), "an unknown kind stays");
        assertEquals("old#2", ld.path("removedAccounts").get(0).path("name").asText());
        assertEquals(60, root.path("emulators").path("nox").path("fields").path("fps").asInt());
        assertEquals("x", root.path("instances").path("nox/Nox-1").path("tags").get(0).asText());
    }

    @Test
    void aKnownKeyWhoseContentIsAllUnknownSurvivesAnotherChange(@TempDir Path dir) throws IOException {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        Files.writeString(store.file(), """
                {"v": 1,
                 "emulators": {"mumu": {"fields": {"hdr": true}},
                               "LDPlayer": {"colour": "blue"}},
                 "instances": {"ldplayer/LDPlayer-1": {"fields": {"hdr": true}, "colour": "blue",
                                                      "games": [{"bundle": "x"}], "tags": [{"name": "farm"}]}}}
                """, StandardCharsets.UTF_8);

        store.update(c -> c.withInstance(new InstanceKey(PlatformId.MEMU, "MEmu"), Layer.EMPTY.withTags(List.of("t"))));
        store.update(c -> c.withInstance(LD1, Layer.EMPTY.withTags(List.of("farm"))));

        JsonNode root = JsonFiles.read(store.file());
        assertTrue(root.path("emulators").path("mumu").path("fields").path("hdr").asBoolean());
        assertEquals("blue", root.path("emulators").path("LDPlayer").path("colour").asText());
        JsonNode ld1 = root.path("instances").path(LD1.text());
        assertEquals("blue", ld1.path("colour").asText());
        assertEquals("x", ld1.path("games").get(0).path("bundle").asText(), "an unread game entry stays");
        assertEquals("farm", ld1.path("tags").get(0).asText());
        assertEquals("farm", ld1.path("tags").get(1).path("name").asText(), "an unread tag entry stays");
    }

    @Test
    void aRenameMovesTheWholeNode(@TempDir Path dir) throws IOException {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        Files.writeString(store.file(), """
                {"v": 1, "instances": {"ldplayer/LDPlayer-1": {"tags": ["farm"], "colour": "blue"}}}
                """, StandardCharsets.UTF_8);
        InstanceKey renamed = new InstanceKey(PlatformId.LDPLAYER, "Farm-1");

        EmulatorConfig after = store.rename(LD1, renamed);

        assertEquals(List.of("farm"), after.instance(renamed).tags());
        assertFalse(after.instances().containsKey(LD1));
        assertEquals("blue", JsonFiles.read(store.file()).path("instances").path(renamed.text()).path("colour").asText());
    }

    @Test
    void aChangeInsideAChangeIsRefusedInWords(@TempDir Path dir) {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        assertThrows(IllegalStateException.class, () -> store.update(c -> {
            try {
                new SavedFilters(dir).load();
                new SavedFilters(dir).remove(SavedFilters.Tab.RUNNING, "x");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return c;
        }));
    }

    @Test
    void aLayerDroppedIsRemovedFromTheFile(@TempDir Path dir) throws IOException {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        store.update(c -> c.withInstance(LD1, Layer.EMPTY.withTags(List.of("farm"))));

        store.update(c -> c.withoutInstance(LD1));

        assertFalse(JsonFiles.read(store.file()).path("instances").has(LD1.text()));
    }

    @Test
    void anUnreadableFileIsNeitherEmptyNorOverwritten(@TempDir Path dir) throws IOException {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        Files.writeString(store.file(), "{ not json", StandardCharsets.UTF_8);

        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.update(c -> c.withInstance(LD1, Layer.EMPTY.withTags(List.of("a")))));
        assertEquals("{ not json", Files.readString(store.file(), StandardCharsets.UTF_8));
    }

    @Test
    void concurrentUpdatesLoseNothing(@TempDir Path dir) throws Exception {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<EmulatorConfig>> done = IntStream.range(0, 20).mapToObj(i -> pool.submit(() -> store.update(
                    c -> c.withInstance(new InstanceKey(PlatformId.MUMU, "MuMu-" + i), Layer.EMPTY.withTags(List.of("t"))))))
                    .toList();
            for (Future<EmulatorConfig> each : done) {
                each.get();
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(20, store.load().instances().size());
    }

    @Test
    void theFileHasNoPlaceForAPassword(@TempDir Path dir) throws IOException {
        EmulatorConfigStore store = new EmulatorConfigStore(dir);
        store.update(c -> c.withEmulator(PlatformId.LDPLAYER,
                Layer.EMPTY.withAccount(new AccountRecord(AccountRecord.Kind.GOOGLE, "pc@example.com", "main"))));

        String text = Files.readString(store.file(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertFalse(text.contains("password") || text.contains("token") || text.contains("secret"));
    }
}
