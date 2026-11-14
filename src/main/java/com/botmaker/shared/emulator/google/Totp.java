package com.botmaker.shared.emulator.google;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * A time-based one-time code (RFC 6238): HMAC-SHA1 over the 30-second window's number, six digits, as Google's
 * Authenticator method gives one from its setup key (spec {@code 2026-11-14-end-to-end-instances-design.md} § The
 * authenticator key). The
 * key and every code are arrays the caller clears; nothing here keeps or logs one.
 */
public final class Totp {

    /** How long one code holds. */
    public static final Duration STEP = Duration.ofSeconds(30);
    /** How many digits Google's codes have. */
    public static final int DIGITS = 6;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    /** The window {@code epochMillis} falls in: the number the code is made from. */
    public static long window(long epochMillis) {
        return Math.floorDiv(epochMillis, STEP.toMillis());
    }

    /** The {@link #DIGITS}-digit code of {@code key} for the window {@code epochMillis} falls in. */
    public static char[] code(byte[] key, long epochMillis) {
        return code(key, window(epochMillis), DIGITS);
    }

    /** The {@code digits}-digit code of {@code key} for {@code window}, zero-padded on the left. */
    static char[] code(byte[] key, long window, int digits) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(window).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("this Java has no HMAC-SHA1", e);
        }
        int at = hash[hash.length - 1] & 0x0F;
        int truncated = ((hash[at] & 0x7F) << 24) | ((hash[at + 1] & 0xFF) << 16) | ((hash[at + 2] & 0xFF) << 8)
                | (hash[at + 3] & 0xFF);
        Arrays.fill(hash, (byte) 0);
        long modulus = 1;
        for (int i = 0; i < digits; i++) modulus *= 10;
        long value = truncated % modulus;
        char[] out = new char[digits];
        for (int i = digits - 1; i >= 0; i--) {
            out[i] = (char) ('0' + value % 10);
            value /= 10;
        }
        return out;
    }

    /**
     * A setup key as Google shows it, in its one kept form: its Base32 letters upper case, without spaces, dashes, tabs
     * or the padding {@code =}. Empty when it holds any other sign, or fewer than 16 letters (80 bits): not a key Google
     * gives. The caller clears both arrays.
     */
    public static Optional<char[]> canonical(char[] key) {
        if (key == null) return Optional.empty();
        char[] letters = new char[key.length];
        int count = 0;
        for (char c : key) {
            if (c == ' ' || c == '-' || c == '=' || c == '\t') continue;
            char upper = Character.toUpperCase(c);
            if (BASE32.indexOf(upper) < 0) {
                Arrays.fill(letters, '\0');
                return Optional.empty();
            }
            letters[count++] = upper;
        }
        char[] out = count < 16 ? null : Arrays.copyOf(letters, count);
        Arrays.fill(letters, '\0');
        return Optional.ofNullable(out);
    }

    /** The bytes of a setup key as Google shows it ({@link #canonical}); empty when it is not one. */
    public static Optional<byte[]> decode(char[] key) {
        Optional<char[]> letters = canonical(key);
        if (letters.isEmpty()) return Optional.empty();
        char[] base32 = letters.get();
        byte[] out = new byte[base32.length * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char letter : base32) {
            buffer = (buffer << 5) | BASE32.indexOf(letter);
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        Arrays.fill(base32, '\0');
        return Optional.of(out);
    }
}
