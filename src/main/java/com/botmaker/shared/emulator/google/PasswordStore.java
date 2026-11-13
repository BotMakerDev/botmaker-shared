package com.botmaker.shared.emulator.google;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Where the Google passwords the user asked BotMaker to remember on this PC are kept: the OS credential store
 * ({@link KeyringStore}), or {@link #NONE} when there is none. An entry is keyed by its email, lower-cased; nothing
 * else is ever kept in it, and no method logs or returns a password but {@link #read}.
 */
public interface PasswordStore {

    /** No store: nothing remembered, nothing kept. */
    PasswordStore NONE = new PasswordStore() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<char[]> read(String email) {
            return Optional.empty();
        }

        @Override
        public boolean remember(String email, char[] password) {
            return false;
        }

        @Override
        public void forget(String email) {
        }
    };

    /** Whether this PC has a store that answers; the window offers *Remember on this PC* only then. */
    boolean available();

    /** {@code email}'s remembered password, a fresh array the caller clears; empty when none is kept or the store fails. */
    Optional<char[]> read(String email);

    /** Keeps {@code password} for {@code email}; false when the store refused it. {@code password} is not cleared. */
    boolean remember(String email, char[] password);

    /** Forgets {@code email}'s password, if one is kept; a store that fails leaves it as it was. */
    void forget(String email);

    /** Whether a password is kept for {@code email}: read and cleared at once. */
    default boolean remembered(String email) {
        Optional<char[]> password = read(email);
        password.ifPresent(chars -> Arrays.fill(chars, '\0'));
        return password.isPresent();
    }

    /** The key an entry is kept under: the email stripped and lower-cased. */
    static String key(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
