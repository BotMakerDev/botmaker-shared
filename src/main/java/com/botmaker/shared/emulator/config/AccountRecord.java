// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * An account a configuration says an instance should hold, or one the book keeps for a launch target. Google accounts
 * are signed in by BotMaker; Facebook and mail are records only (the user's call, 2026-11-12); a launch target's
 * account is the user's account in a game, which a run is dealt (spec {@code 2026-11-27-claimed-accounts-design.md}).
 * Never a password: there is no field for one.
 *
 * @param name        the email, or the account's name: its main address
 * @param note        the user's own words, or empty
 * @param aliases     the account's other addresses, lower case, each once, never the main one
 * @param verify      the second step BotMaker follows when Google asks for one
 * @param target      a {@link Kind#LAUNCH_TARGET} account's launch target as its spec is written
 *                    ({@code emu-app:com.game}, {@code steam:12345}); {@code ""} for any other kind
 * @param signsInWith the book entry a launch target's account signs in with (a Google, Facebook or mail account), or
 *                    none; always none for another kind
 * @param playsAt     the place a launch target's account plays at, by the place's key ({@code this-pc/android/MEmu_1}),
 *                    or {@code ""} for any free place; always {@code ""} for another kind
 */
public record AccountRecord(Kind kind, String name, String note, List<String> aliases, Verification verify,
                            String target, Optional<Key> signsInWith, String playsAt) {

    public AccountRecord {
        if (kind == null || kind == Kind.UNKNOWN) {
            throw new IllegalArgumentException("an account has a known kind");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an account has a name");
        }
        target = target == null ? "" : target.strip();
        signsInWith = signsInWith == null ? Optional.empty() : signsInWith;
        playsAt = playsAt == null ? "" : playsAt.strip();
        if (kind == Kind.LAUNCH_TARGET) {
            if (target.isEmpty()) throw new IllegalArgumentException("a launch target's account names its launch target");
            if (signsInWith.isPresent() && signsInWith.get().kind() == Kind.LAUNCH_TARGET) {
                throw new IllegalArgumentException("a launch target's account signs in with a Google, Facebook or mail account");
            }
        } else {
            target = "";
            signsInWith = Optional.empty();
            playsAt = "";
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

    /** A Google, Facebook or mail account: no launch target, so no link. */
    public AccountRecord(Kind kind, String name, String note, List<String> aliases, Verification verify) {
        this(kind, name, note, aliases, verify, "", Optional.empty(), "");
    }

    /** An account with no alias that follows the default verification: what the book's older Java names. */
    public AccountRecord(Kind kind, String name, String note) {
        this(kind, name, note, List.of(), Verification.DEFAULT);
    }

    public AccountRecord(Kind kind, String name) {
        this(kind, name, "");
    }

    /** {@code target}'s account {@code name}, with no link yet. */
    public static AccountRecord ofLaunchTarget(String target, String name, String note) {
        return new AccountRecord(Kind.LAUNCH_TARGET, name, note, List.of(), Verification.DEFAULT, target,
                Optional.empty(), "");
    }

    /**
     * What identifies the account: its kind, its launch target and its name, case aside, so two launch targets may
     * each have an account called {@code main}. An alias is not part of it.
     */
    public Key key() {
        return new Key(kind, target, name);
    }

    /**
     * The account's key under each of its addresses, its own first: what an instance or a configuration that names
     * one of them is matched by, a launch target's account under its target.
     */
    public List<Key> keys() {
        return addresses().stream().map(address -> new Key(kind, target, address)).toList();
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
        return new AccountRecord(kind, name, next, aliases, verify, target, signsInWith, playsAt);
    }

    public AccountRecord withAliases(List<String> next) {
        return new AccountRecord(kind, name, note, next, verify, target, signsInWith, playsAt);
    }

    /** This launch target's account signing in with {@code next}, or with none. */
    public AccountRecord withSignsInWith(Optional<Key> next) {
        return new AccountRecord(kind, name, note, aliases, verify, target, next, playsAt);
    }

    /** This launch target's account playing at the place keyed {@code next}, or at any free place for {@code ""}. */
    public AccountRecord withPlaysAt(String next) {
        return new AccountRecord(kind, name, note, aliases, verify, target, signsInWith, next);
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
        return new AccountRecord(kind, name, note, aliases, next, target, signsInWith, playsAt);
    }

    /**
     * An account's identity: two records of one key are one account, whatever their notes.
     *
     * @param target a launch target's account's launch target; {@code ""} for any other kind
     */
    public record Key(Kind kind, String target, String name) {
        public Key {
            target = target == null || kind != Kind.LAUNCH_TARGET ? "" : target.strip();
            name = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        }

        /** The key of a Google, Facebook or mail account. */
        public Key(Kind kind, String name) {
            this(kind, "", name);
        }

        /**
         * The key as the book writes a link to it: {@code google:main@gmail.com}. Only a Google, Facebook or mail
         * account is linked to, so a launch target is never part of it.
         */
        public String text() {
            return kind.id() + ":" + name;
        }

        /** The key {@link #text()} wrote; empty for text that is none, or names a kind this build does not know. */
        public static Optional<Key> parse(String text) {
            int colon = text == null ? -1 : text.indexOf(':');
            if (colon <= 0 || text.substring(colon + 1).isBlank()) return Optional.empty();
            Kind kind = Kind.fromId(text.substring(0, colon).strip());
            if (kind == Kind.UNKNOWN || kind == Kind.LAUNCH_TARGET) return Optional.empty();
            return Optional.of(new Key(kind, text.substring(colon + 1)));
        }
    }

    public enum Kind {
        GOOGLE("google", "Google"),
        FACEBOOK("facebook", "Facebook"),
        MAIL("mail", "Mail"),
        /** A user's account in a game, kept for its launch target; BotMaker does not sign it in (2026-11-27). */
        LAUNCH_TARGET("launch-target", "Launch target"),
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
