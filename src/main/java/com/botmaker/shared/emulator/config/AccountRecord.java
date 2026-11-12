package com.botmaker.shared.emulator.config;

import java.util.Locale;

/**
 * An account a configuration says an instance should hold. Google accounts are signed in by BotMaker; Facebook and
 * mail are records only (the user's call, 2026-11-12). Never a password: there is no field for one.
 *
 * @param name the email, or the account's name
 * @param note the user's own words, or empty
 */
public record AccountRecord(Kind kind, String name, String note) {

    public AccountRecord {
        if (kind == null || kind == Kind.UNKNOWN) {
            throw new IllegalArgumentException("an account has a known kind");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an account has a name");
        }
        name = name.strip();
        note = note == null ? "" : note.strip();
    }

    public AccountRecord(Kind kind, String name) {
        this(kind, name, "");
    }

    /** What identifies the account: its kind and name, case aside. */
    public Key key() {
        return new Key(kind, name);
    }

    /** An account's identity: two records of one key are one account, whatever their notes. */
    public record Key(Kind kind, String name) {
        public Key {
            name = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        }
    }

    public enum Kind {
        GOOGLE("google", "Google"),
        FACEBOOK("facebook", "Facebook"),
        MAIL("mail", "Mail"),
        UNKNOWN("unknown", "Account");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** Whether BotMaker signs this kind in; the others are records only. */
        public boolean signedInByBotMaker() {
            return this == GOOGLE;
        }

        public static Kind fromId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equals(id)) {
                    return kind;
                }
            }
            return UNKNOWN;
        }
    }
}
