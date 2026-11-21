// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The round trip through the OS credential store, under a test service of its own, deleted after. */
class KeyringStoreTest {

    private static final String SERVICE = "BotMaker Google test";
    private static final String EMAIL = "round-trip@example.com";

    /** A plugin's code runs on a host thread whose context loader is the host's: the Secret Service opens all the same. */
    @Test
    @EnabledOnOs(OS.LINUX)
    void opensOnAThreadWhoseContextLoaderIsAnothers() {
        assumeTrue(new KeyringStore(SERVICE).available(), "no Secret Service in this session");
        Thread thread = Thread.currentThread();
        ClassLoader was = thread.getContextClassLoader();
        thread.setContextClassLoader(new ClassLoader(null) {});
        try {
            assertTrue(new KeyringStore(SERVICE).available());
        } finally {
            thread.setContextClassLoader(was);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
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
