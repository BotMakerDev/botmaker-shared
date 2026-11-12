package com.botmaker.shared.emulator.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One layer of a configuration: an emulator's, which its instances start from, or an instance's own, over it.
 *
 * @param removedAccounts accounts the emulator's layer lists that this instance should not hold
 * @param defaultGoogle   the Google account a game's chooser offers first, or null for the first one listed
 * @param removedGames    packages the emulator's layer lists that this instance should not have
 * @param tags            the user's labels for an instance ("farm", "test")
 */
public record Layer(Map<ConfigField, ConfigValue> fields, List<AccountRecord> accounts,
                    Set<AccountRecord.Key> removedAccounts, String defaultGoogle, List<GameRecord> games,
                    Set<String> removedGames, List<String> tags) {

    public static final Layer EMPTY = new Layer(Map.of(), List.of(), Set.of(), null, List.of(), Set.of(), List.of());

    public Layer {
        EnumMap<ConfigField, ConfigValue> checked = new EnumMap<>(ConfigField.class);
        (fields == null ? Map.<ConfigField, ConfigValue>of() : fields).forEach((field, value) -> {
            if (field == ConfigField.UNKNOWN || value == null) {
                return;
            }
            if (value.kind() != field.kind()) {
                throw new IllegalArgumentException(field.id() + " takes a " + field.kind() + ", not " + value.kind());
            }
            checked.put(field, value);
        });
        fields = Collections.unmodifiableMap(checked);
        accounts = distinctAccounts(accounts == null ? List.of() : accounts);
        removedAccounts = ordered(removedAccounts);
        defaultGoogle = defaultGoogle == null || defaultGoogle.isBlank() ? null : defaultGoogle.strip();
        games = distinctGames(games == null ? List.of() : games);
        removedGames = ordered(removedGames);
        tags = List.copyOf(new LinkedHashSet<>((tags == null ? List.<String>of() : tags).stream()
                .filter(tag -> tag != null && !tag.isBlank()).map(String::strip).toList()));
    }

    public boolean isEmpty() {
        return equals(EMPTY);
    }

    public Layer with(ConfigField field, ConfigValue value) {
        Map<ConfigField, ConfigValue> next = new EnumMap<>(ConfigField.class);
        next.putAll(fields);
        next.put(field, value);
        return new Layer(next, accounts, removedAccounts, defaultGoogle, games, removedGames, tags);
    }

    /** This layer without its own value for the field: an instance's falls back to its emulator's. */
    public Layer without(ConfigField field) {
        Map<ConfigField, ConfigValue> next = new EnumMap<>(ConfigField.class);
        next.putAll(fields);
        next.remove(field);
        return new Layer(next, accounts, removedAccounts, defaultGoogle, games, removedGames, tags);
    }

    /** Adds the account, or replaces the one of its key (its note); it is no longer removed. */
    public Layer withAccount(AccountRecord account) {
        List<AccountRecord> next = new ArrayList<>(accounts.stream().filter(a -> !a.key().equals(account.key())).toList());
        next.add(account);
        Set<AccountRecord.Key> removed = new LinkedHashSet<>(removedAccounts);
        removed.remove(account.key());
        return new Layer(fields, next, removed, defaultGoogle, games, removedGames, tags);
    }

    /** Drops the layer's own account of that key, and marks it removed so an inherited one is dropped too. */
    public Layer removingAccount(AccountRecord.Key key) {
        Set<AccountRecord.Key> removed = new LinkedHashSet<>(removedAccounts);
        removed.add(key);
        return new Layer(fields, accounts.stream().filter(a -> !a.key().equals(key)).toList(), removed,
                defaultGoogle, games, removedGames, tags);
    }

    /** Takes the account back: neither listed here nor removed, so the emulator's layer decides. */
    public Layer inheritingAccount(AccountRecord.Key key) {
        Set<AccountRecord.Key> removed = new LinkedHashSet<>(removedAccounts);
        removed.remove(key);
        return new Layer(fields, accounts.stream().filter(a -> !a.key().equals(key)).toList(), removed,
                defaultGoogle, games, removedGames, tags);
    }

    public Layer withDefaultGoogle(String email) {
        return new Layer(fields, accounts, removedAccounts, email, games, removedGames, tags);
    }

    public Layer withGame(GameRecord game) {
        List<GameRecord> next = new ArrayList<>(games.stream()
                .filter(g -> !g.packageName().equals(game.packageName())).toList());
        next.add(game);
        Set<String> removed = new LinkedHashSet<>(removedGames);
        removed.remove(game.packageName());
        return new Layer(fields, accounts, removedAccounts, defaultGoogle, next, removed, tags);
    }

    public Layer removingGame(String packageName) {
        Set<String> removed = new LinkedHashSet<>(removedGames);
        removed.add(packageName);
        return new Layer(fields, accounts, removedAccounts, defaultGoogle,
                games.stream().filter(g -> !g.packageName().equals(packageName)).toList(), removed, tags);
    }

    /** The tags, blank and null ones dropped. */
    public Layer withTags(Collection<String> next) {
        return new Layer(fields, accounts, removedAccounts, defaultGoogle, games, removedGames, new ArrayList<>(next));
    }

    /** An unmodifiable copy that keeps the given order, so the file is written the same way each time. */
    static <T> Set<T> ordered(Collection<T> given) {
        Set<T> out = new LinkedHashSet<>();
        if (given != null) {
            given.stream().filter(Objects::nonNull).forEach(out::add);
        }
        return Collections.unmodifiableSet(out);
    }

    /** One record per key, the last given winning, in the order the keys first came. */
    private static List<AccountRecord> distinctAccounts(List<AccountRecord> given) {
        Map<AccountRecord.Key, AccountRecord> byKey = new LinkedHashMap<>();
        for (AccountRecord account : given) {
            if (account != null) {
                byKey.put(account.key(), account);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static List<GameRecord> distinctGames(List<GameRecord> given) {
        Map<String, GameRecord> byPackage = new LinkedHashMap<>();
        for (GameRecord game : given) {
            if (game != null) {
                byPackage.put(game.packageName(), game);
            }
        }
        return List.copyOf(byPackage.values());
    }
}
