package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.PlatformId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every configuration on this PC: each emulator's layer, and each instance's own over it (spec
 * {@code 2026-11-12-emulators-window-design.md}, the user's call: layered, field by field). Kept by the SDK
 * plugin as its values of this PC, which convert to and from this model; a value, so a change is a new one.
 */
public record EmulatorConfig(Map<PlatformId, Layer> emulators, Map<InstanceKey, Layer> instances) {

    public static final EmulatorConfig EMPTY = new EmulatorConfig(Map.of(), Map.of());

    public EmulatorConfig {
        EnumMap<PlatformId, Layer> byPlatform = new EnumMap<>(PlatformId.class);
        (emulators == null ? Map.<PlatformId, Layer>of() : emulators).forEach((platform, layer) -> {
            if (platform != PlatformId.UNKNOWN && layer != null && !layer.isEmpty()) {
                byPlatform.put(platform, layer);
            }
        });
        emulators = Collections.unmodifiableMap(byPlatform);
        Map<InstanceKey, Layer> byInstance = new LinkedHashMap<>();
        (instances == null ? Map.<InstanceKey, Layer>of() : instances).forEach((key, layer) -> {
            Layer own = layer == null ? null : instanceOnly(layer);
            if (key != null && own != null && !own.isEmpty()) {
                byInstance.put(key, own);
            }
        });
        instances = Collections.unmodifiableMap(byInstance);
    }

    /** The layer without the fields only an emulator's layer holds, which an instance's never applies. */
    private static Layer instanceOnly(Layer layer) {
        Layer out = layer;
        for (ConfigField field : layer.fields().keySet()) {
            if (field.emulatorOnly()) {
                out = out.without(field);
            }
        }
        return out;
    }

    public Layer emulator(PlatformId platform) {
        return emulators.getOrDefault(platform, Layer.EMPTY);
    }

    public Layer instance(InstanceKey key) {
        return instances.getOrDefault(key, Layer.EMPTY);
    }

    public EmulatorConfig withEmulator(PlatformId platform, Layer layer) {
        Map<PlatformId, Layer> next = new EnumMap<>(PlatformId.class);
        next.putAll(emulators);
        next.put(platform, layer);
        return new EmulatorConfig(next, instances);
    }

    /** Sets the instance's own layer; one holding a field only an emulator's layer holds is refused. */
    public EmulatorConfig withInstance(InstanceKey key, Layer layer) {
        layer.fields().keySet().stream().filter(ConfigField::emulatorOnly).findFirst().ifPresent(field -> {
            throw new IllegalArgumentException(field.id() + " is set on an emulator, not on an instance");
        });
        Map<InstanceKey, Layer> next = new LinkedHashMap<>(instances);
        next.put(key, layer);
        return new EmulatorConfig(emulators, next);
    }

    public EmulatorConfig withoutInstance(InstanceKey key) {
        Map<InstanceKey, Layer> next = new LinkedHashMap<>(instances);
        next.remove(key);
        return new EmulatorConfig(emulators, next);
    }

    /** What the instance should be: its emulator's layer, then its own over it. */
    public Wanted wanted(InstanceKey key) {
        Layer base = emulator(key.platform());
        Layer own = instance(key);

        Map<ConfigField, Sourced<ConfigValue>> fields = new EnumMap<>(ConfigField.class);
        base.fields().forEach((field, value) -> {
            if (!field.emulatorOnly()) {
                fields.put(field, new Sourced<>(value, Sourced.Source.EMULATOR));
            }
        });
        own.fields().forEach((field, value) -> {
            if (!field.emulatorOnly()) {
                fields.put(field, new Sourced<>(value, Sourced.Source.INSTANCE));
            }
        });

        Map<AccountRecord.Key, Sourced<AccountRecord>> accounts = new LinkedHashMap<>();
        for (AccountRecord account : base.accounts()) {
            if (!own.removedAccounts().contains(account.key())) {
                accounts.put(account.key(), new Sourced<>(account, Sourced.Source.EMULATOR));
            }
        }
        for (AccountRecord account : own.accounts()) {
            accounts.put(account.key(), new Sourced<>(account, Sourced.Source.INSTANCE));
        }
        Set<AccountRecord.Key> removed = new LinkedHashSet<>(own.removedAccounts());
        removed.removeAll(accounts.keySet());

        Map<String, Sourced<GameRecord>> games = new LinkedHashMap<>();
        for (GameRecord game : base.games()) {
            if (!own.removedGames().contains(game.packageName())) {
                games.put(game.packageName(), new Sourced<>(game, Sourced.Source.EMULATOR));
            }
        }
        for (GameRecord game : own.games()) {
            games.put(game.packageName(), new Sourced<>(game, Sourced.Source.INSTANCE));
        }
        Set<String> removedGames = new LinkedHashSet<>(own.removedGames());
        removedGames.removeAll(games.keySet());

        List<Sourced<AccountRecord>> listed = new ArrayList<>(accounts.values());
        return new Wanted(fields, listed, removed, defaultGoogle(listed, own, base), new ArrayList<>(games.values()),
                removedGames, own.tags());
    }

    /** The instance's choice, else its emulator's, when still wanted; else the first Google account listed. */
    private static Optional<String> defaultGoogle(List<Sourced<AccountRecord>> listed, Layer own, Layer base) {
        List<String> google = listed.stream().map(Sourced::value)
                .filter(account -> account.kind() == AccountRecord.Kind.GOOGLE).map(AccountRecord::name).toList();
        for (String choice : new String[]{own.defaultGoogle(), base.defaultGoogle()}) {
            if (choice != null) {
                Optional<String> found = google.stream().filter(choice::equalsIgnoreCase).findFirst();
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return google.stream().findFirst();
    }
}
