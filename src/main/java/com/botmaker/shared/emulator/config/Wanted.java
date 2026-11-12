package com.botmaker.shared.emulator.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What an instance should be: its emulator's layer with its own over it ({@link EmulatorConfig#wanted}).
 *
 * @param removedAccounts accounts the instance should not hold although its emulator's layer may list them; Apply
 *                        signs one out only where the instance holds it, so a key nothing lists any more is harmless
 * @param defaultGoogle   the Google account offered first, or empty when the instance should hold none
 */
public record Wanted(Map<ConfigField, Sourced<ConfigValue>> fields, List<Sourced<AccountRecord>> accounts,
                     Set<AccountRecord.Key> removedAccounts, Optional<String> defaultGoogle,
                     List<Sourced<GameRecord>> games, Set<String> removedGames, List<String> tags) {

    public Wanted {
        Map<ConfigField, Sourced<ConfigValue>> byField = new EnumMap<>(ConfigField.class);
        byField.putAll(fields);
        fields = Collections.unmodifiableMap(byField);
        accounts = List.copyOf(accounts);
        removedAccounts = Layer.ordered(removedAccounts);
        games = List.copyOf(games);
        removedGames = Layer.ordered(removedGames);
        tags = List.copyOf(tags);
    }

    public Optional<Sourced<ConfigValue>> field(ConfigField field) {
        return Optional.ofNullable(fields.get(field));
    }
}
