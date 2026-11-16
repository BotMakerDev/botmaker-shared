// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFC 6238's SHA-1 test vectors, and a setup key as Google shows it. */
class TotpTest {

    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    /** {@link #RFC_KEY} in Base32, grouped and lower case as Google's page shows a key. */
    private static final String RFC_KEY_SHOWN = "gezd gnbv gy3t qojq gezd gnbv gy3t qojq";

    @Test
    void theRfcsVectors() {
        long[] seconds = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] codes = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < seconds.length; i++) {
            assertEquals(codes[i], new String(Totp.code(RFC_KEY, Totp.window(seconds[i] * 1000), 8)), "T=" + seconds[i]);
        }
    }

    @Test
    void sixDigitsAreTheLastSixOfTheRfcs() {
        assertEquals("287082", new String(Totp.code(RFC_KEY, 59_000)));
        assertEquals("081804", new String(Totp.code(RFC_KEY, 1111111109_000L)), "a leading zero kept");
    }

    @Test
    void aKeyAsGoogleShowsItDecodes() {
        assertArrayEquals(RFC_KEY, Totp.decode(RFC_KEY_SHOWN.toCharArray()).orElseThrow());
        assertArrayEquals(RFC_KEY, Totp.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ====".toCharArray()).orElseThrow());
    }

    @Test
    void anythingElseIsNotAKey() {
        assertTrue(Totp.decode("not a key, 1890".toCharArray()).isEmpty(), "1, 8, 9, 0 and signs are not Base32");
        assertTrue(Totp.decode("GEZDGNBV".toCharArray()).isEmpty(), "too short");
        assertTrue(Totp.decode(null).isEmpty());
    }

    @Test
    void theWindowIsThirtySeconds() {
        assertEquals(0, Totp.window(29_999));
        assertEquals(1, Totp.window(30_000));
    }
}
