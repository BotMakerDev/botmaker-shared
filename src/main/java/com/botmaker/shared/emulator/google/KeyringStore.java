package com.botmaker.shared.emulator.google;

import com.github.javakeyring.Keyring;

import java.util.Optional;

/**
 * The OS credential store, through java-keyring: Windows Credential Manager, the Secret Service on Linux, the Keychain
 * on macOS. One entry per remembered account, under the service {@link #SERVICE} and the email lower-cased.
 * <p>
 * java-keyring hands a password over as a {@code String}, which cannot be cleared; it lives only for the call. A store
 * that fails to open is unavailable for the session; a read that fails is no password.
 */
public final class KeyringStore implements PasswordStore {

    /** The service the entries are kept under. */
    public static final String SERVICE = "BotMaker Google";

    private final String service;
    private Keyring keyring;
    private boolean opened;

    /** The store under {@link #SERVICE}. */
    public KeyringStore() {
        this(SERVICE);
    }

    /** The store under {@code service}: a test's own, so it never touches the user's entries. */
    public KeyringStore(String service) {
        this.service = service;
    }

    @Override
    public synchronized boolean available() {
        return keyring() != null;
    }

    @Override
    public synchronized Optional<char[]> read(String email) {
        Keyring store = keyring();
        if (store == null) return Optional.empty();
        try {
            String password = store.getPassword(service, PasswordStore.key(email));
            return password == null || password.isEmpty() ? Optional.empty() : Optional.of(password.toCharArray());
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized boolean remember(String email, char[] password) {
        Keyring store = keyring();
        if (store == null || password == null || password.length == 0) return false;
        try {
            store.setPassword(service, PasswordStore.key(email), new String(password));
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    @Override
    public synchronized void forget(String email) {
        Keyring store = keyring();
        if (store == null) return;
        try {
            store.deletePassword(service, PasswordStore.key(email));
        } catch (Exception | LinkageError ignored) {
            // nothing kept, or the store refused: as it was
        }
    }

    /** The keyring, opened once; null when this PC has none that answers. */
    private Keyring keyring() {
        if (!opened) {
            opened = true;
            try {
                keyring = Keyring.create();
            } catch (Exception | LinkageError e) {
                keyring = null;
            }
        }
        return keyring;
    }
}
