package com.botmaker.shared.emulator.google;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordStoreTest {

    private static final String EMAIL = "user@example.com";

    /** A store in memory, keyed as the real one is; {@code refuses} makes it refuse every write. */
    static final class FakeStore implements PasswordStore {
        final Map<String, char[]> kept = new HashMap<>();
        boolean refuses;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<char[]> read(String email) {
            char[] password = kept.get(PasswordStore.key(email));
            return password == null ? Optional.empty() : Optional.of(password.clone());
        }

        @Override
        public boolean remember(String email, char[] password) {
            if (refuses) return false;
            kept.put(PasswordStore.key(email), password.clone());
            return true;
        }

        @Override
        public void forget(String email) {
            kept.remove(PasswordStore.key(email));
        }
    }

    @Test
    void noneKeepsNothing() {
        assertFalse(PasswordStore.NONE.available());
        assertFalse(PasswordStore.NONE.remember(EMAIL, "pw".toCharArray()));
        assertFalse(PasswordStore.NONE.remembered(EMAIL));
    }

    @Test
    void aRememberedPasswordIsReadAndNotAsked() throws Exception {
        FakeStore store = new FakeStore();
        store.remember(EMAIL, "pw".toCharArray());
        char[] read;
        try (Passwords passwords = new Passwords(store, email -> {
            throw new AssertionError("asked");
        })) {
            read = passwords.of("User@Example.com");
            assertArrayEquals("pw".toCharArray(), read);
        }
        assertArrayEquals(new char[2], read, "the batch clears its copy");
        assertTrue(store.remembered(EMAIL), "the store keeps its own");
    }

    @Test
    void aPasswordIsRememberedOnlyOnceDoneAndOnlyWhenAsked() throws Exception {
        FakeStore store = new FakeStore();
        try (Passwords passwords = new Passwords(store, email -> new Passwords.Answer("pw".toCharArray(), true))) {
            passwords.of(EMAIL);
            assertFalse(store.remembered(EMAIL), "not before the sign-in is done");
            assertTrue(passwords.done(EMAIL));
            assertArrayEquals("pw".toCharArray(), store.read(EMAIL).orElseThrow());
        }
        FakeStore other = new FakeStore();
        try (Passwords passwords = new Passwords(other, email -> Passwords.Answer.once("pw".toCharArray()))) {
            passwords.of(EMAIL);
            assertTrue(passwords.done(EMAIL));
        }
        assertFalse(other.remembered(EMAIL), "this batch only");
    }

    /** Android lists the account under another address, which the configuration names from now on. */
    @Test
    void aPasswordIsKeptUnderTheAddressAndroidLists() throws Exception {
        String listed = "u.ser@example.com";
        FakeStore typed = new FakeStore();
        try (Passwords passwords = new Passwords(typed, email -> new Passwords.Answer("pw".toCharArray(), true))) {
            passwords.of(EMAIL);
            assertTrue(passwords.done(EMAIL, listed));
        }
        assertFalse(typed.remembered(EMAIL));
        assertTrue(typed.remembered(listed));

        FakeStore stored = new FakeStore();
        stored.remember(EMAIL, "pw".toCharArray());
        try (Passwords passwords = new Passwords(stored, email -> null)) {
            passwords.of(EMAIL);
            assertTrue(passwords.done(EMAIL, listed));
        }
        assertFalse(stored.remembered(EMAIL), "moved");
        assertArrayEquals("pw".toCharArray(), stored.read(listed).orElseThrow());
    }

    /** The live screen's *Fill password…*: what the user typed there is the one remembered, once the sign-in is done. */
    @Test
    void aPasswordTypedByTheUserReplacesTheBatchsAndIsRememberedOnceDone() throws Exception {
        FakeStore store = new FakeStore();
        store.remember(EMAIL, "old".toCharArray());
        char[] fromStore;
        char[] typed = "new".toCharArray();
        try (Passwords passwords = new Passwords(store, email -> null)) {
            fromStore = passwords.of(EMAIL);
            assertTrue(passwords.typedByUser(EMAIL, new Passwords.Answer(typed, true)));
            assertArrayEquals("old".toCharArray(), fromStore, "a sign-in may still hold the one it replaced");
            assertArrayEquals("new".toCharArray(), passwords.of(EMAIL));
            assertArrayEquals("old".toCharArray(), store.read(EMAIL).orElseThrow(), "not before the sign-in is done");
            assertTrue(passwords.done(EMAIL));
            assertArrayEquals("new".toCharArray(), store.read(EMAIL).orElseThrow());
        }
        assertArrayEquals(new char[3], typed, "the batch clears it");
        assertArrayEquals(new char[3], fromStore, "and the one it replaced");
    }

    /** Unticked, what the user typed still replaces a password the prompt was asked to remember: it is not kept. */
    @Test
    void aPasswordTypedByTheUserUntickedIsNotRemembered() throws Exception {
        FakeStore store = new FakeStore();
        try (Passwords passwords = new Passwords(store, email -> new Passwords.Answer("wrong".toCharArray(), true))) {
            passwords.of(EMAIL);
            assertTrue(passwords.typedByUser(EMAIL, Passwords.Answer.once("right".toCharArray())));
            assertTrue(passwords.done(EMAIL));
        }
        assertTrue(store.kept.isEmpty());
    }

    @Test
    void aPasswordTypedByTheUserAfterTheSignInIsDoneIsRefused() throws Exception {
        try (Passwords passwords = new Passwords(new FakeStore(), email -> Passwords.Answer.once("pw".toCharArray()))) {
            passwords.of(EMAIL);
            passwords.done(EMAIL);
            char[] late = "pw".toCharArray();
            assertFalse(passwords.typedByUser(EMAIL, new Passwords.Answer(late, true)));
            assertArrayEquals(new char[2], late);
        }
    }

    @Test
    void aPasswordTypedByTheUserForADroppedAccountOrAClosedBatchIsClearedAndRefused() throws Exception {
        Passwords passwords = new Passwords(new FakeStore(), email -> null);
        passwords.drop(EMAIL);
        char[] typed = "pw".toCharArray();
        assertFalse(passwords.typedByUser(EMAIL, new Passwords.Answer(typed, true)));
        assertArrayEquals(new char[2], typed);
        passwords.close();
        char[] late = "pw".toCharArray();
        assertFalse(passwords.typedByUser("other@example.com", new Passwords.Answer(late, true)));
        assertArrayEquals(new char[2], late);
    }

    @Test
    void aStoreThatRefusesSaysSo() throws Exception {
        FakeStore store = new FakeStore();
        store.refuses = true;
        try (Passwords passwords = new Passwords(store, email -> new Passwords.Answer("pw".toCharArray(), true))) {
            passwords.of(EMAIL);
            assertFalse(passwords.done(EMAIL));
            assertTrue(passwords.done(EMAIL), "said once");
        }
    }

    @Test
    void refusedForgetsAStoredPasswordAndDropLeavesIt() throws Exception {
        FakeStore store = new FakeStore();
        store.remember(EMAIL, "pw".toCharArray());
        try (Passwords passwords = new Passwords(store, email -> null)) {
            passwords.of(EMAIL);
            passwords.drop(EMAIL);
            assertTrue(passwords.left(EMAIL));
        }
        assertTrue(store.remembered(EMAIL), "a sign-in Google refused for another reason keeps the password");
        try (Passwords passwords = new Passwords(store, email -> null)) {
            passwords.of(EMAIL);
            passwords.refused(EMAIL);
            assertNull(passwords.of(EMAIL));
        }
        assertFalse(store.remembered(EMAIL));
    }

    @Test
    void aTypedPasswordGoogleRefusesIsNeitherKeptNorForgottenFromElsewhere() throws Exception {
        FakeStore store = new FakeStore();
        List<String> asked = new ArrayList<>();
        try (Passwords passwords = new Passwords(store, email -> {
            asked.add(email);
            return new Passwords.Answer("pw".toCharArray(), true);
        })) {
            char[] typed = passwords.of(EMAIL);
            passwords.refused(EMAIL);
            assertTrue(Arrays.equals(new char[2], typed));
            assertTrue(passwords.done(EMAIL), "nothing left to remember");
        }
        assertEquals(List.of(EMAIL), asked);
        assertTrue(store.kept.isEmpty());
    }
}
