// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import java.util.Arrays;
import java.util.Optional;

/**
 * The authenticator setup keys the user gave BotMaker, one per Google account that follows the method
 * {@code Verification.AUTHENTICATOR} (spec {@code 2026-11-14-end-to-end-instances-design.md} § The authenticator key):
 * kept only in the OS credential store, under a service of their own ({@link #SERVICE}) beside the passwords', keyed by
 * the account's main address, and typed by the user in BotMaker's own dialog. No method returns a key; {@link #code}
 * gives the code for a time, which the caller types and clears. Nothing here logs a key or a code.
 */
public final class AuthenticatorKeys {

    /** The service the keys are kept under in the OS credential store. */
    public static final String SERVICE = "BotMaker Google authenticator";

    /** No keys: every account's code is asked of the user. */
    public static final AuthenticatorKeys NONE = new AuthenticatorKeys(PasswordStore.NONE);

    private final PasswordStore store;

    /** The keys kept in {@code store}: {@code new KeyringStore(SERVICE)}, or a test's own. */
    public AuthenticatorKeys(PasswordStore store) {
        this.store = store == null ? PasswordStore.NONE : store;
    }

    /** Whether this PC has a store that answers: the window offers to keep a key only then. */
    public boolean available() {
        return store.available();
    }

    /** Whether a key is kept for {@code email}. */
    public boolean has(String email) {
        return store.remembered(email);
    }

    /** What a {@link #put} said. */
    public enum Put {
        KEPT,
        /** Not a key Google shows: Base32 letters, at least 16 of them. */
        NOT_A_KEY,
        /** The store refused it, or this PC has none. */
        REFUSED
    }

    /**
     * Keeps {@code key} for {@code email} in its one form ({@link Totp#canonical}), as the user pasted it;
     * {@code key} is not cleared. Nothing is kept unless it reads as a key.
     */
    public Put put(String email, char[] key) {
        Optional<char[]> kept = Totp.canonical(key);
        if (kept.isEmpty()) return Put.NOT_A_KEY;
        try {
            return store.remember(email, kept.get()) ? Put.KEPT : Put.REFUSED;
        } finally {
            Arrays.fill(kept.get(), '\0');
        }
    }

    /** Forgets {@code email}'s key, if one is kept. */
    public void forget(String email) {
        store.forget(email);
    }

    /**
     * Moves {@code from}'s key to {@code to}, one account merged into another: kept under {@code to} unless it holds
     * one already, then forgotten under {@code from}. False when the store refused the move, which leaves both as
     * they were.
     */
    public boolean move(String from, String to) {
        Optional<char[]> key = store.read(from);
        if (key.isEmpty()) return true;
        try {
            if (!store.remembered(to) && !store.remember(to, key.get())) return false;
        } finally {
            Arrays.fill(key.get(), '\0');
        }
        store.forget(from);
        return true;
    }

    /** The code {@code email}'s key gives at {@code epochMillis}, which the caller clears; empty when no key reads. */
    public Optional<char[]> code(String email, long epochMillis) {
        Optional<char[]> key = store.read(email);
        if (key.isEmpty()) return Optional.empty();
        Optional<byte[]> bytes;
        try {
            bytes = Totp.decode(key.get());
        } finally {
            Arrays.fill(key.get(), '\0');
        }
        if (bytes.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Totp.code(bytes.get(), epochMillis));
        } finally {
            Arrays.fill(bytes.get(), (byte) 0);
        }
    }
}
