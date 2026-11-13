package com.botmaker.shared.emulator.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The round trip through Windows Credential Manager, under a test service of its own, deleted after. */
@EnabledOnOs(OS.WINDOWS)
class KeyringStoreTest {

    private static final String SERVICE = "BotMaker Google test";
    private static final String EMAIL = "round-trip@example.com";

    @Test
    void aPasswordGoesInComesBackAndIsForgotten() {
        KeyringStore store = new KeyringStore(SERVICE);
        assumeTrue(store.available(), "no credential store in this session (a service account)");
        try {
            assertTrue(store.remember(EMAIL, "not-a-real-password".toCharArray()));
            assertArrayEquals("not-a-real-password".toCharArray(), store.read("Round-Trip@Example.com").orElseThrow());
        } finally {
            store.forget(EMAIL);
        }
        assertFalse(store.remembered(EMAIL));
        store.forget(EMAIL); // forgetting what isn't kept is quiet
    }
}
