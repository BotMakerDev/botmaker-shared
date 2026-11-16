// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An account a configuration says an instance should hold. Google accounts are signed in by BotMaker; Facebook and
 * mail are records only (the user's call, 2026-11-12). Never a password: there is no field for one.
 *
 * @param name    the email, or the account's name: its main address
 * @param note    the user's own words, or empty
 * @param aliases the account's other addresses, lower case, each once, never the main one
 * @param verify  the second step BotMaker follows when Google asks for one
 */
public record AccountRecord(Kind kind, String name, String note, List<String> aliases, Verification verify) {

    public AccountRecord {
        if (kind == null || kind == Kind.UNKNOWN) {
            throw new IllegalArgumentException("an account has a known kind");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an account has a name");
        }
        name = name.strip();
        note = note == null ? "" : note.strip();
        String main = name.toLowerCase(Locale.ROOT);
        Set<String> kept = new LinkedHashSet<>();
        if (aliases != null) {
            for (String alias : aliases) {
                String address = alias == null ? "" : alias.strip().toLowerCase(Locale.ROOT);
                if (!address.isEmpty() && !address.equals(main)) kept.add(address);
            }
        }
        aliases = List.copyOf(kept);
        verify = verify == null ? Verification.DEFAULT : verify;
    }

    /** An account with no alias that follows the default verification: what the book's older Java names. */
    public AccountRecord(Kind kind, String name, String note) {
        this(kind, name, note, List.of(), Verification.DEFAULT);
    }

    public AccountRecord(Kind kind, String name) {
        this(kind, name, "");
    }

    /** What identifies the account: its kind and name, case aside. An alias is not part of it. */
    public Key key() {
        return new Key(kind, name);
    }

    /** The main address, then the aliases. */
    public List<String> addresses() {
        List<String> all = new ArrayList<>(aliases.size() + 1);
        all.add(name);
        all.addAll(aliases);
        return List.copyOf(all);
    }

    /** Whether {@code address} is one of this account's, the main one or an alias, case and spaces aside. */
    public boolean answersTo(String address) {
        if (address == null) return false;
        String wanted = address.strip().toLowerCase(Locale.ROOT);
        return !wanted.isEmpty() && (name.toLowerCase(Locale.ROOT).equals(wanted) || aliases.contains(wanted));
    }

    public AccountRecord withNote(String next) {
        return new AccountRecord(kind, name, next, aliases, verify);
    }

    public AccountRecord withAliases(List<String> next) {
        return new AccountRecord(kind, name, note, next, verify);
    }

    /** This account with {@code address} among its aliases too; the same account when it answers to it already. */
    public AccountRecord withAlias(String address) {
        if (answersTo(address)) return this;
        List<String> next = new ArrayList<>(aliases);
        next.add(address);
        return withAliases(next);
    }

    public AccountRecord withoutAlias(String address) {
        String gone = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
        return withAliases(aliases.stream().filter(alias -> !alias.equals(gone)).toList());
    }

    public AccountRecord withVerify(Verification next) {
        return new AccountRecord(kind, name, note, aliases, next);
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

    /**
     * The second step a Google sign-in follows (spec {@code 2026-11-14-google-signin-design.md}). A code is typed by
     * the user in BotMaker each time and never stored, a backup code included.
     */
    public enum Verification {
        /** The book's default, set in the Accounts window's header. */
        DEFAULT("default", "Default"),
        /** Google's "Tap Yes on your phone", approved by the user on their phone. */
        PHONE("phone", "Phone prompt"),
        /** A texted or authenticator code. */
        CODE("code", "Code (texted or app)"),
        /**
         * Google's Authenticator app method, its code made by BotMaker from the setup key the user gave it, kept in the
         * OS credential store ({@code AuthenticatorKeys}); without a key, the code is asked as {@link #CODE}'s is.
         */
        AUTHENTICATOR("authenticator", "Authenticator (BotMaker holds the key)"),
        /** One of the account's backup codes. */
        BACKUP_CODE("backup-code", "Backup code"),
        /** No preference: the screen is handed to the user. */
        ASK("ask", "Ask me"),
        /** One a newer BotMaker wrote; followed as {@link #ASK}. */
        UNKNOWN("unknown", "A newer BotMaker's");

        /** What the book's default is before the user sets one. */
        public static final Verification FIRST_DEFAULT = PHONE;

        private final String id;
        private final String displayName;

        Verification(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The methods a person picks from: the account's own choice adds {@link #DEFAULT} before them. */
        public static List<Verification> methods() {
            return List.of(PHONE, CODE, AUTHENTICATOR, BACKUP_CODE, ASK);
        }

        /**
         * The methods the book's default may be: not {@link #AUTHENTICATOR}, which needs each account's own key and so
         * is the user's per account.
         */
        public static List<Verification> defaults() {
            return List.of(PHONE, CODE, BACKUP_CODE, ASK);
        }

        /** Whether this is a method of its own, one the book's default may be. */
        public boolean isMethod() {
            return methods().contains(this);
        }

        /** The method followed: this one, or {@code byDefault} for {@link #DEFAULT}, and {@link #ASK} for an unknown. */
        public Verification followed(Verification byDefault) {
            if (this == DEFAULT) {
                return byDefault != null && defaults().contains(byDefault) ? byDefault : FIRST_DEFAULT;
            }
            return isMethod() ? this : ASK;
        }

        public static Verification fromId(String id) {
            if (id == null || id.isBlank()) return DEFAULT;
            for (Verification method : values()) {
                if (method.id.equals(id.strip())) return method;
            }
            return UNKNOWN;
        }
    }
}
