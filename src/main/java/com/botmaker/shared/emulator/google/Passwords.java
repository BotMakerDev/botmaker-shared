package com.botmaker.shared.emulator.google;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The passwords of one batch of sign-ins, in memory only: each asked once, by the caller's {@link Prompt} (the window's
 * dialog), and every one cleared when the batch closes, whatever its end. An account whose sign-in must not be tried
 * again (a wrong password) is dropped: its password cleared, and never asked again in the batch.
 */
public final class Passwords implements AutoCloseable {

    /** Asks the user for one account's password. */
    @FunctionalInterface
    public interface Prompt {
        /** The password the user typed for {@code email}, or null when they skipped it. The batch clears it. */
        char[] ask(String email) throws InterruptedException;
    }

    private final Prompt prompt;
    private final Map<String, char[]> held = new HashMap<>();
    private final Set<String> skipped = new HashSet<>();
    private boolean closed;

    public Passwords(Prompt prompt) {
        this.prompt = prompt;
    }

    /** {@code email}'s password, asked the first time; null when the user skipped it or it was dropped. */
    public synchronized char[] of(String email) throws InterruptedException {
        if (closed) throw new IllegalStateException("the batch's passwords are cleared");
        String key = key(email);
        if (skipped.contains(key)) return null;
        char[] password = held.get(key);
        if (password != null) return password;
        password = prompt.ask(email);
        if (password == null || password.length == 0) {
            skipped.add(key);
            return null;
        }
        held.put(key, password);
        return password;
    }

    /** Clears {@code email}'s password and never asks for it again in this batch. */
    public synchronized void drop(String email) {
        String key = key(email);
        char[] password = held.remove(key);
        if (password != null) Arrays.fill(password, '\0');
        skipped.add(key);
    }

    /** Whether {@code email} was skipped or dropped. */
    public synchronized boolean left(String email) {
        return skipped.contains(key(email));
    }

    @Override
    public synchronized void close() {
        held.values().forEach(password -> Arrays.fill(password, '\0'));
        held.clear();
        closed = true;
    }

    private static String key(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
