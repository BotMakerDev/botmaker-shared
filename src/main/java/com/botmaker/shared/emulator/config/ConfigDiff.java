package com.botmaker.shared.emulator.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Where an instance differs from what its configuration says it should be ({@link Wanted} against
 * {@link InstanceFacts}): each wanted field's state, the Google accounts to sign in or out, and the games missing. A field
 * its product cannot apply is {@link Status#BY_HAND}, never a change; Facebook and mail accounts are records only, so
 * never a change either.
 *
 * @param accountsRead whether Android listed the accounts; when not (the instance is stopped), every wanted Google
 *                     account is a sign-in, which reads the instance first and passes one it holds, and no removal is
 *                     said
 * @param gamesRead    whether Android listed the packages; when not (the instance is stopped), every wanted game is an
 *                     install, which passes one the instance has ({@code UP_TO_DATE}), and none is said to differ
 */
public record ConfigDiff(List<FieldState> fields, List<AccountChange> accounts, List<GameRecord> missingGames,
                         boolean accountsRead, boolean gamesRead) {

    public ConfigDiff {
        fields = List.copyOf(fields);
        accounts = List.copyOf(accounts);
        missingGames = List.copyOf(missingGames);
    }

    /** A wanted field against what the instance has. */
    public record FieldState(ConfigField field, Sourced<ConfigValue> wanted, Optional<InstanceFacts.Read> now,
                             Status status) {
    }

    public enum Status {
        /** The instance has it. */
        SAME("same", "As configured"),
        /** Apply makes it so. */
        CHANGE("change", "Apply changes it"),
        /** This product's setting BotMaker cannot write: the user sets it in the emulator. */
        BY_HAND("by_hand", "Set by hand on this emulator"),
        /** BotMaker sets it as each run starts (Do Not Disturb). */
        EACH_RUN("each_run", "Set for each run"),
        /** Neither the product nor Android said what the instance has. */
        UNREAD("unread", "Not read"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Status(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Status fromId(String id) {
            for (Status status : values()) {
                if (status.id.equals(id)) return status;
            }
            return UNKNOWN;
        }
    }

    /** A Google account to sign in or out. */
    public record AccountChange(AccountRecord account, Op op) {
        public enum Op { ADD, REMOVE }
    }

    /**
     * Whether the instance differs anywhere: a change, a field set by hand that was read and is not as wanted, an
     * account it was read not to hold, a game. An account on an instance not read is no known difference.
     */
    public boolean differs() {
        return fields.stream().anyMatch(f -> f.status() == Status.CHANGE
                        || f.status() == Status.BY_HAND && f.now().isPresent())
                || accountsRead && !accounts.isEmpty() || gamesRead && !missingGames.isEmpty();
    }

    /** Whether Apply has something to do here. */
    public boolean applicable() {
        return fields.stream().anyMatch(f -> f.status() == Status.CHANGE) || !accounts.isEmpty()
                || !missingGames.isEmpty();
    }

    public static ConfigDiff of(Wanted wanted, InstanceFacts facts) {
        ProductSettings product = ProductSettings.of(facts.key().platform());
        List<FieldState> fields = new ArrayList<>();
        wanted.fields().forEach((field, value) -> {
            Optional<InstanceFacts.Read> now = facts.field(field);
            fields.add(new FieldState(field, value, now, status(product, field, value.value(), now)));
        });

        Optional<AndroidState> android = facts.android();
        Optional<Set<String>> held = android.flatMap(AndroidState::googleAccounts);
        List<AccountChange> accounts = new ArrayList<>();
        for (Sourced<AccountRecord> account : wanted.accounts()) {
            AccountRecord record = account.value();
            boolean isHeld = held.map(emails -> emails.contains(lower(record.name()))).orElse(false);
            if (record.kind().signedInByBotMaker() && !isHeld) {
                accounts.add(new AccountChange(record, AccountChange.Op.ADD));
            }
        }
        held.ifPresent(emails -> {
            for (AccountRecord.Key removed : wanted.removedAccounts()) {
                if (removed.kind().signedInByBotMaker() && emails.contains(removed.name())) {
                    accounts.add(new AccountChange(new AccountRecord(removed.kind(), removed.name()),
                            AccountChange.Op.REMOVE));
                }
            }
        });

        Optional<Set<String>> packages = android.flatMap(AndroidState::packages);
        List<GameRecord> missing = wanted.games().stream().map(Sourced::value)
                .filter(game -> packages.map(installed -> !installed.contains(game.packageName())).orElse(true))
                .toList();
        return new ConfigDiff(fields, accounts, missing, held.isPresent(), packages.isPresent());
    }

    private static Status status(ProductSettings product, ConfigField field, ConfigValue wanted,
                                 Optional<InstanceFacts.Read> now) {
        if (product.setEachRun(field)) return Status.EACH_RUN;
        if (now.isPresent() && same(field, wanted, now.get().value())) return Status.SAME;
        if (!product.applies(field)) return Status.BY_HAND;
        return now.isPresent() ? Status.CHANGE : Status.UNREAD;
    }

    /** Equal values; a locale as Android reads one ({@code en_US} is {@code en-US}), case aside. */
    static boolean same(ConfigField field, ConfigValue wanted, ConfigValue now) {
        if (field == ConfigField.LOCALE && wanted instanceof ConfigValue.Text(String a)
                && now instanceof ConfigValue.Text(String b)) {
            return a.replace('_', '-').equalsIgnoreCase(b.replace('_', '-'));
        }
        return wanted.equals(now);
    }

    private static String lower(String text) {
        return text.strip().toLowerCase(Locale.ROOT);
    }
}
