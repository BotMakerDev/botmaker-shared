// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The passwords of one batch of sign-ins, in memory only: each read from the {@link PasswordStore} when the user asked
 * this PC to remember it, else asked once by the caller's {@link Prompt} (the window's dialog), and every one cleared
 * when the batch closes, whatever its end.
 * <p>
 * A password the user asked to remember is kept in the store as soon as it is typed (2026-11-15, the user's call), so a
 * sign-in cancelled or stopped on a page BotMaker doesn't know doesn't ask for it again; a store that refuses it then
 * is tried again once the sign-in is {@link #done}. An account whose sign-in must not be tried again is
 * {@link #drop}ped: its password cleared and never asked again in the batch, a kept one kept. One Google
 * {@link #refused} is forgotten by the store, so a wrong password is never typed again without asking, and so is one
 * this batch kept that has nothing to sign in ({@link #discard}: no such account, or one the instance can't type).
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
    /** Accounts whose password the store refused when it was typed: tried again once the sign-in is done. */
    private final Set<String> toRemember = new HashSet<>();
    /** Accounts whose password this batch put in the store as it was typed: taken out again when the user unticks. */
    private final Set<String> keptNow = new HashSet<>();
    /** The accounts whose sign-in is done: a password typed for one afterwards has nothing left to be remembered by. */
    private final Set<String> finished = new HashSet<>();
    /** Passwords {@link #typedByUser} replaced, which a sign-in may still hold: cleared when the batch closes. */
    private final List<char[]> replaced = new ArrayList<>();
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
        if (answer.remember()) keepNow(email);
        return answer.password();
    }

    /** {@code email}'s held password into the store now; refused, tried again once the sign-in is done. */
    private void keepNow(String email) {
        String key = PasswordStore.key(email);
        if (store.remember(email, held.get(key))) {
            stored.add(key);
            keptNow.add(key);
            toRemember.remove(key);
        } else {
            toRemember.add(key);
        }
    }

    /**
     * {@code email} is signed in: a password the store refused as it was typed is tried there again. False only when
     * the store refused it again.
     */
    public synchronized boolean done(String email) {
        return done(email, null);
    }

    /**
     * {@code email} is signed in and Android lists it as {@code listedAs} (null: as typed), the address the
     * configuration names from now on: a password the store refused as it was typed is tried there again, and one the
     * store holds under the typed address moves to that one. False only when the store refused to keep it.
     */
    public synchronized boolean done(String email, String listedAs) {
        String key = PasswordStore.key(email);
        finished.add(key);
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

    /**
     * The user typed {@code email}'s password on the instance themselves (the live screen's *Fill password…*): the
     * batch holds it in place of any it had, and remembers it now if they asked (unticked, one the batch kept as
     * typed is forgotten). The
     * batch clears it; false, and the password cleared, when the batch is over, left the account out, or already
     * signed it in. Takes the batch's lock, which a prompt being answered holds: never call it on the prompt's thread.
     */
    public synchronized boolean typedByUser(String email, Answer answer) {
        String key = PasswordStore.key(email);
        if (closed || skipped.contains(key) || finished.contains(key)
                || answer.password() == null || answer.password().length == 0) {
            if (answer.password() != null) Arrays.fill(answer.password(), '\0');
            return false;
        }
        char[] was = held.put(key, answer.password());
        // a sign-in may still be using the one it replaces: cleared when the batch closes, not now
        if (was != null && was != answer.password()) replaced.add(was);
        toRemember.remove(key);
        // the one this batch kept as it was typed goes either way; one kept before the batch stays
        boolean keptBefore = stored.remove(key) && !keptNow.contains(key);
        if (keptNow.remove(key)) store.forget(email);
        if (answer.remember()) {
            keepNow(email);
            // the store refused the new one: the old one is still there, and a wrong password must still forget it
            if (keptBefore && !stored.contains(key)) stored.add(key);
        }
        return true;
    }

    /**
     * Google took {@code email}'s password, though the sign-in may still fail past it (a second step): one the store
     * refused as it was typed is tried there again, so a retry doesn't ask for it. False only when the store refused it.
     */
    public synchronized boolean accepted(String email) {
        String key = PasswordStore.key(email);
        char[] password = held.get(key);
        if (password == null || !toRemember.contains(key)) return true;
        if (!store.remember(email, password)) return false;
        toRemember.remove(key);
        stored.add(key);
        return true;
    }

    /**
     * The password typed for {@code email} has nothing to sign in: Google knows no such account, or the instance
     * cannot type it. Dropped, and forgotten by the store when this batch kept it, so the next Apply asks again; one
     * kept before the batch stays.
     */
    public synchronized void discard(String email) {
        if (keptNow.remove(PasswordStore.key(email))) store.forget(email);
        drop(email);
    }

    /** Clears {@code email}'s password and never asks for it again in this batch; a kept one stays kept. */
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
        replaced.forEach(password -> Arrays.fill(password, '\0'));
        replaced.clear();
        toRemember.clear();
        keptNow.clear();
        closed = true;
    }
}
