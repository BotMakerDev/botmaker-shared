package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.PlatformId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code configurations.json} to and from {@link EmulatorConfig}. Writing edits the tree that was read, touching only
 * what this build understands: a key, a field id, an account kind, an entry or a product it does not know is kept as
 * it was, so an older BotMaker never erases a newer one's data. A known field whose value it cannot read is kept too,
 * and a layer's node is removed only when the layer was read from it and the change dropped it.
 */
final class ConfigJson {

    static final int VERSION = 1;

    private static final String EMULATORS = "emulators";
    private static final String INSTANCES = "instances";
    private static final String FIELDS = "fields";
    private static final String ACCOUNTS = "accounts";
    private static final String REMOVED_ACCOUNTS = "removedAccounts";
    private static final String DEFAULT_GOOGLE = "defaultGoogle";
    private static final String GAMES = "games";
    private static final String REMOVED_GAMES = "removedGames";
    private static final String TAGS = "tags";

    private ConfigJson() {}

    /** The configuration in the tree; a key not written exactly as this build writes it is not its own. */
    static EmulatorConfig read(ObjectNode root) {
        Map<PlatformId, Layer> emulators = new EnumMap<>(PlatformId.class);
        root.path(EMULATORS).properties().forEach(entry -> {
            PlatformId platform = platform(entry.getKey());
            if (platform != null && entry.getValue().isObject()) {
                emulators.put(platform, layer(entry.getValue()));
            }
        });
        Map<InstanceKey, Layer> instances = new LinkedHashMap<>();
        root.path(INSTANCES).properties().forEach(entry -> {
            InstanceKey key = InstanceKey.parse(entry.getKey());
            if (key != null && entry.getValue().isObject()) {
                instances.put(key, layer(entry.getValue()));
            }
        });
        return new EmulatorConfig(emulators, instances);
    }

    /** Writes {@code next} into the tree {@code before} was read from. */
    static void write(EmulatorConfig before, EmulatorConfig next, ObjectNode root) {
        root.put("v", Math.max(VERSION, root.path("v").asInt(VERSION)));

        Map<String, Layer> emulators = new LinkedHashMap<>();
        next.emulators().forEach((platform, layer) -> emulators.put(platform.id(), layer));
        Set<String> emulatorsBefore = new LinkedHashSet<>();
        before.emulators().keySet().forEach(platform -> emulatorsBefore.add(platform.id()));
        writeLayers(object(root, EMULATORS), emulators, emulatorsBefore);

        Map<String, Layer> instances = new LinkedHashMap<>();
        next.instances().forEach((key, layer) -> instances.put(key.text(), layer));
        Set<String> instancesBefore = new LinkedHashSet<>();
        before.instances().keySet().forEach(key -> instancesBefore.add(key.text()));
        writeLayers(object(root, INSTANCES), instances, instancesBefore);
    }

    /** Sets each layer; removes a node only when its layer was read and is gone now; keeps every other node. */
    private static void writeLayers(ObjectNode parent, Map<String, Layer> layers, Set<String> readBefore) {
        Iterator<String> names = parent.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (readBefore.contains(name) && !layers.containsKey(name)) {
                names.remove();
            }
        }
        layers.forEach((name, layer) -> writeLayer(object(parent, name), layer));
    }

    /** The product a key names, only when written as {@link PlatformId#id()} writes it. */
    static PlatformId platform(String key) {
        PlatformId platform = PlatformId.fromId(key);
        return platform != PlatformId.UNKNOWN && platform.id().equals(key) ? platform : null;
    }

    private static Layer layer(JsonNode node) {
        Map<ConfigField, ConfigValue> fields = new EnumMap<>(ConfigField.class);
        node.path(FIELDS).properties().forEach(entry -> {
            ConfigField field = ConfigField.fromId(entry.getKey());
            ConfigValue value = value(field, entry.getValue());
            if (value != null) {
                fields.put(field, value);
            }
        });
        List<AccountRecord> accounts = new ArrayList<>();
        for (JsonNode entry : node.path(ACCOUNTS)) {
            AccountRecord account = account(entry);
            if (account != null) {
                accounts.add(account);
            }
        }
        Set<AccountRecord.Key> removedAccounts = new LinkedHashSet<>();
        for (JsonNode entry : node.path(REMOVED_ACCOUNTS)) {
            AccountRecord.Key key = removedKey(entry);
            if (key != null) {
                removedAccounts.add(key);
            }
        }
        List<GameRecord> games = new ArrayList<>();
        for (JsonNode entry : node.path(GAMES)) {
            String packageName = gamePackage(entry);
            if (packageName != null) {
                games.add(new GameRecord(packageName, entry.path("label").asText(null)));
            }
        }
        return new Layer(fields, accounts, removedAccounts, node.path(DEFAULT_GOOGLE).asText(null), games,
                strings(node.path(REMOVED_GAMES)), new ArrayList<>(strings(node.path(TAGS))));
    }

    private static void writeLayer(ObjectNode node, Layer layer) {
        ObjectNode fields = object(node, FIELDS);
        Iterator<Map.Entry<String, JsonNode>> present = fields.fields();
        while (present.hasNext()) {
            Map.Entry<String, JsonNode> entry = present.next();
            ConfigField field = ConfigField.fromId(entry.getKey());
            boolean understood = value(field, entry.getValue()) != null;
            if (understood && !layer.fields().containsKey(field)) {
                present.remove();
            }
        }
        layer.fields().forEach((field, value) -> fields.set(field.id(), json(value, node)));

        Map<AccountRecord.Key, ObjectNode> oldAccounts = new HashMap<>();
        List<JsonNode> keptAccounts = new ArrayList<>();
        for (JsonNode entry : node.path(ACCOUNTS)) {
            AccountRecord account = account(entry);
            if (account != null && entry instanceof ObjectNode object) {
                oldAccounts.put(account.key(), object);
            } else {
                keptAccounts.add(entry);
            }
        }
        ArrayNode accounts = node.arrayNode();
        for (AccountRecord account : layer.accounts()) {
            ObjectNode entry = oldAccounts.containsKey(account.key())
                    ? oldAccounts.get(account.key()).deepCopy() : node.objectNode();
            entry.put("kind", account.kind().id());
            entry.put("name", account.name());
            entry.put("note", account.note());
            accounts.add(entry);
        }
        keptAccounts.forEach(accounts::add);
        node.set(ACCOUNTS, accounts);

        ArrayNode removed = node.arrayNode();
        for (AccountRecord.Key key : layer.removedAccounts()) {
            removed.addObject().put("kind", key.kind().id()).put("name", key.name());
        }
        for (JsonNode entry : node.path(REMOVED_ACCOUNTS)) {
            if (removedKey(entry) == null) {
                removed.add(entry);
            }
        }
        node.set(REMOVED_ACCOUNTS, removed);

        if (layer.defaultGoogle() == null) {
            node.remove(DEFAULT_GOOGLE);
        } else {
            node.put(DEFAULT_GOOGLE, layer.defaultGoogle());
        }

        Map<String, ObjectNode> oldGames = new HashMap<>();
        List<JsonNode> keptGames = new ArrayList<>();
        for (JsonNode entry : node.path(GAMES)) {
            String packageName = gamePackage(entry);
            if (packageName != null && entry instanceof ObjectNode object) {
                oldGames.put(packageName, object);
            } else {
                keptGames.add(entry);
            }
        }
        ArrayNode games = node.arrayNode();
        for (GameRecord game : layer.games()) {
            ObjectNode entry = oldGames.containsKey(game.packageName())
                    ? oldGames.get(game.packageName()).deepCopy() : node.objectNode();
            entry.put("package", game.packageName());
            entry.put("label", game.label());
            games.add(entry);
        }
        keptGames.forEach(games::add);
        node.set(GAMES, games);

        node.set(REMOVED_GAMES, strings(node, REMOVED_GAMES, layer.removedGames()));
        node.set(TAGS, strings(node, TAGS, layer.tags()));
    }

    /** The value, or null when the field is unknown or the JSON is not of its kind. */
    static ConfigValue value(ConfigField field, JsonNode json) {
        try {
            return switch (field.kind()) {
                case SCREEN -> json.isObject() && json.path("w").isInt() && json.path("h").isInt()
                        && json.path("dpi").isInt()
                        ? new ConfigValue.Screen(json.get("w").asInt(), json.get("h").asInt(), json.get("dpi").asInt())
                        : null;
                case COUNT -> json.isInt() ? new ConfigValue.Count(json.asInt()) : null;
                case MEGABYTES -> json.isInt() ? new ConfigValue.Megabytes(json.asInt()) : null;
                case TOGGLE -> json.isBoolean() ? new ConfigValue.Toggle(json.asBoolean()) : null;
                case TEXT -> field != ConfigField.UNKNOWN && json.isTextual() ? new ConfigValue.Text(json.asText()) : null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static JsonNode json(ConfigValue value, ObjectNode factory) {
        return switch (value) {
            case ConfigValue.Screen screen -> factory.objectNode()
                    .put("w", screen.width()).put("h", screen.height()).put("dpi", screen.dpi());
            case ConfigValue.Count count -> factory.numberNode(count.value());
            case ConfigValue.Megabytes megabytes -> factory.numberNode(megabytes.value());
            case ConfigValue.Toggle toggle -> factory.booleanNode(toggle.on());
            case ConfigValue.Text text -> factory.textNode(text.value());
        };
    }

    private static AccountRecord account(JsonNode entry) {
        AccountRecord.Kind kind = AccountRecord.Kind.fromId(entry.path("kind").asText());
        String name = entry.path("name").asText("");
        return kind == AccountRecord.Kind.UNKNOWN || name.isBlank()
                ? null : new AccountRecord(kind, name, entry.path("note").asText(""));
    }

    private static AccountRecord.Key removedKey(JsonNode entry) {
        AccountRecord.Kind kind = AccountRecord.Kind.fromId(entry.path("kind").asText());
        String name = entry.path("name").asText("");
        return kind == AccountRecord.Kind.UNKNOWN || name.isBlank() ? null : new AccountRecord.Key(kind, name);
    }

    private static String gamePackage(JsonNode entry) {
        String packageName = entry.path("package").asText("");
        return entry.isObject() && !packageName.isBlank() ? packageName : null;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode entry : array) {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                out.add(entry.asText());
            }
        }
        return out;
    }

    /** The strings given, then the array's entries this build does not read as strings, kept. */
    private static ArrayNode strings(ObjectNode node, String name, Iterable<String> values) {
        ArrayNode out = node.arrayNode();
        values.forEach(out::add);
        for (JsonNode entry : node.path(name)) {
            if (!entry.isTextual() || entry.asText().isBlank()) {
                out.add(entry);
            }
        }
        return out;
    }

    private static ObjectNode object(ObjectNode parent, String name) {
        JsonNode child = parent.get(name);
        return child instanceof ObjectNode object ? object : parent.putObject(name);
    }
}
