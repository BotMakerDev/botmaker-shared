package com.botmaker.shared.emulator.google;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The passwords of one batch of sign-ins, in memory only: each read from the {@link PasswordStore} when the user asked
 * this PC to remember it, else asked once by the caller's {@link Prompt} (the window's dialog), and every one cleared
 * when the batch closes, whatever its end.
 * <p>
 * A password the user asked to remember is kept in the store only once its sign-in is {@link #done}. An account whose
 * sign-in must not be tried again is {@link #drop}ped: its password cleared and never asked again in the batch; one
 * Google {@link #refused} is forgotten by the store too.
 */
public final class Passwords implements AutoCloseable {

    /** What the user answered for one account: the password, and whether to remember it on this PC. */
    public record Answer(char[] password, boolean remember) {

        /** A password for this batch only. */
        public static Answer once(char[] password) {
            return new Answer(password, false);
        }
    }

    /** Asks the user for one account's password. */
    @FunctionalInterface
    public interface Prompt {
        /** What the user answered for {@code email}, or null when they skipped it. The batch clears the password. */
        Answer ask(String email) throws InterruptedException;
    }

    private final PasswordStore store;
    private final Prompt prompt;
    private final Map<String, char[]> held = new HashMap<>();
    private final Set<String> skipped = new HashSet<>();
    private final Set<String> stored = new HashSet<>();
    private final Set<String> toRemember = new HashSet<>();
    private boolean closed;

    /** A batch whose passwords are all asked, none remembered. */
    public Passwords(Prompt prompt) {
        this(PasswordStore.NONE, prompt);
    }

    public Passwords(PasswordStore store, Prompt prompt) {
        this.store = store;
        this.prompt = prompt;
    }

    /** {@code email}'s password, read from the store or asked the first time; null when skipped or dropped. */
    public synchronized char[] of(String email) throws InterruptedException {
        if (closed) throw new IllegalStateException("the batch's passwords are cleared");
        String key = PasswordStore.key(email);
        if (skipped.contains(key)) return null;
        char[] password = held.get(key);
        if (password != null) return password;
        Optional<char[]> remembered = store.read(email);
        if (remembered.isPresent()) {
            held.put(key, remembered.get());
            stored.add(key);
            return remembered.get();
        }
        Answer answer = prompt.ask(email);
        if (answer == null || answer.password() == null || answer.password().length == 0) {
            skipped.add(key);
            return null;
        }
        held.put(key, answer.password());
        if (answer.remember()) toRemember.add(key);
        return answer.password();
    }

    /**
     * {@code email} is signed in: its password is remembered if the user asked. False only when the store refused
     * to keep it.
     */
    public synchronized boolean done(String email) {
        return done(email, null);
    }

    /**
     * {@code email} is signed in and Android lists it as {@code listedAs} (null: as typed), the address the
     * configuration names from now on: its password is remembered under that one if the user asked, and one the
     * store held under the typed address moves there. False only when the store refused to keep it.
     */
    public synchronized boolean done(String email, String listedAs) {
        String key = PasswordStore.key(email);
        char[] password = held.get(key);
        if (password == null) return true;
        boolean moves = listedAs != null && !PasswordStore.key(listedAs).equals(key);
        if (!toRemember.remove(key) && !(moves && stored.contains(key))) return true;
        String keptAs = moves ? listedAs : email;
        if (!store.remember(keptAs, password)) return false;
        if (moves) store.forget(email);
        stored.add(PasswordStore.key(keptAs));
        return true;
    }

    /** Clears {@code email}'s password and never asks for it again in this batch. */
    public synchronized void drop(String email) {
        String key = PasswordStore.key(email);
        char[] password = held.remove(key);
        if (password != null) Arrays.fill(password, '\0');
        toRemember.remove(key);
        skipped.add(key);
    }

    /** Google refused {@code email}'s password: dropped, and forgotten by the store when it came from there. */
    public synchronized void refused(String email) {
        String key = PasswordStore.key(email);
        if (stored.remove(key)) store.forget(email);
        drop(email);
    }

    /** Whether {@code email} was skipped or dropped. */
    public synchronized boolean left(String email) {
        return skipped.contains(PasswordStore.key(email));
    }

    @Override
    public synchronized void close() {
        held.values().forEach(password -> Arrays.fill(password, '\0'));
        held.clear();
        toRemember.clear();
        closed = true;
    }
}
