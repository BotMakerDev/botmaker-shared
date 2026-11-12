package com.botmaker.shared.emulator.manage;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shapes only: the values are drawn, and no real device's ID is written here. */
class DeviceIdsTest {

    @Test
    void eachIdHasItsShape() {
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            String androidId = DeviceIds.androidId(random);
            assertTrue(androidId.matches("[0-9a-f]{16}") && !androidId.equals("0".repeat(16)), androidId);

            String mac = DeviceIds.mac(random);
            assertTrue(mac.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}"), mac);
            int first = Integer.parseInt(mac.substring(0, 2), 16);
            assertEquals(0x02, first & 0x03, "locally administered, unicast: " + mac);

            String imei = DeviceIds.imei(random);
            assertTrue(imei.matches("35\\d{13}"), imei);
            assertEquals(imei.charAt(14) - '0', DeviceIds.luhn(imei.substring(0, 14)), imei);
        }
    }

    @Test
    void luhnAgreesWithTheTextbookExample() {
        assertEquals(3, DeviceIds.luhn("7992739871"));
    }

    @Test
    void twoDrawsDiffer() {
        Random random = new Random(1);
        assertNotEquals(DeviceIds.imei(random), DeviceIds.imei(random));
    }
}
