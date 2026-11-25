// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidPageTest {

    @Test
    void aDoorIsReadByItsIdAndOpensByItsIntent() {
        AndroidPage page = AndroidPage.parse("google-sign-in").orElseThrow();

        assertEquals(new AndroidPage.Door(AndroidDoor.GOOGLE_SIGN_IN), page);
        assertEquals("google-sign-in", page.id());
        assertEquals("am start -W -a android.settings.ADD_ACCOUNT_SETTINGS --esa account_types com.google",
                page.startCommand());
    }

    @Test
    void aComponentIsAnExportedActivity() {
        AndroidPage page = AndroidPage.parse(" com.android.settings/.wifi.WifiSettings ").orElseThrow();

        assertEquals(new AndroidPage.Activity("com.android.settings/.wifi.WifiSettings"), page);
        assertEquals(List.of("-n", "com.android.settings/.wifi.WifiSettings"), page.intentArgs());
    }

    @Test
    void aBlankOrUnknownPageIsNone() {
        assertEquals(Optional.empty(), AndroidPage.parse(null));
        assertEquals(Optional.empty(), AndroidPage.parse(" "));
        assertEquals(Optional.empty(), AndroidPage.parse("a-door-of-a-newer-botmaker"));
        assertEquals(AndroidDoor.UNKNOWN, AndroidDoor.fromId("nope"));
    }

    @Test
    void theDoorsIntoAPackageAreListed() {
        assertEquals(List.of(AndroidDoor.GOOGLE_SIGN_IN), AndroidDoor.into("com.google.android.gms"));
        assertTrue(AndroidDoor.into("com.android.settings").contains(AndroidDoor.WIFI));
        assertEquals(List.of(), AndroidDoor.into("com.zjcs.android.us"));
    }
}
