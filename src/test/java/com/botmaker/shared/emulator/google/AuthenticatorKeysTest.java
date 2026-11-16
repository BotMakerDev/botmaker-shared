// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys kept in a store of their own, one form whatever was pasted, a code given and never the key. */
class AuthenticatorKeysTest {

    private static final String EMAIL = "user@example.com";
    static final String KEY = "gezd gnbv gy3t qojq gezd gnbv gy3t qojq";

    @Test
    void aKeyIsKeptInOneFormAndGivesItsCode() {
        PasswordStoreTest.FakeStore store = new PasswordStoreTest.FakeStore();
        AuthenticatorKeys keys = new AuthenticatorKeys(store);
        char[] pasted = KEY.toCharArray();
        assertEquals(AuthenticatorKeys.Put.KEPT, keys.put("User@Example.com ", pasted));
        assertEquals(KEY, new String(pasted), "the caller's array is the caller's to clear");
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", new String(store.kept.get(EMAIL)));
        assertTrue(keys.has(EMAIL));
        assertEquals("287082", new String(keys.code(EMAIL, 59_000).orElseThrow()));
        keys.forget(EMAIL);
        assertFalse(keys.has(EMAIL));
        assertTrue(keys.code(EMAIL, 59_000).isEmpty());
    }

    @Test
    void whatIsNotAKeyIsNotKept() {
        PasswordStoreTest.FakeStore store = new PasswordStoreTest.FakeStore();
        assertEquals(AuthenticatorKeys.Put.NOT_A_KEY, new AuthenticatorKeys(store).put(EMAIL, "my password".toCharArray()));
        assertTrue(store.kept.isEmpty());
        store.refuses = true;
        assertEquals(AuthenticatorKeys.Put.REFUSED, new AuthenticatorKeys(store).put(EMAIL, KEY.toCharArray()));
    }

    @Test
    void noneHasNoKey() {
        assertFalse(AuthenticatorKeys.NONE.available());
        assertEquals(AuthenticatorKeys.Put.REFUSED, AuthenticatorKeys.NONE.put(EMAIL, KEY.toCharArray()));
        assertTrue(AuthenticatorKeys.NONE.code(EMAIL, 0).isEmpty());
    }
}
