package com.botmaker.shared.emulator.manage;

import java.util.HexFormat;
import java.util.random.RandomGenerator;

/**
 * Device IDs drawn for a console that takes them as values (MuMu's {@code simulation}). Each is drawn fresh and
 * handed to the console only: never logged, shown or kept.
 */
final class DeviceIds {

    private DeviceIds() {}

    /** 16 lower-case hex digits, as Android's {@code Settings.Secure.ANDROID_ID}; never all zeros. */
    static String androidId(RandomGenerator random) {
        long id;
        do {
            id = random.nextLong();
        } while (id == 0);
        return HexFormat.of().toHexDigits(id);
    }

    /** A locally administered unicast MAC, {@code 02:xx:…}-style: it can't collide with a vendor's address. */
    static String mac(RandomGenerator random) {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        bytes[0] = (byte) ((bytes[0] & 0xFC) | 0x02);
        return HexFormat.ofDelimiter(":").formatHex(bytes);
    }

    /** 15 digits: a phone's {@code 35} reporting-body prefix, 12 drawn, and the Luhn check digit. */
    static String imei(RandomGenerator random) {
        StringBuilder digits = new StringBuilder("35");
        while (digits.length() < 14) digits.append(random.nextInt(10));
        return digits.append(luhn(digits)).toString();
    }

    /** The digit that makes {@code body} + it pass the Luhn check. */
    static int luhn(CharSequence body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = body.charAt(body.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return (10 - sum % 10) % 10;
    }
}
