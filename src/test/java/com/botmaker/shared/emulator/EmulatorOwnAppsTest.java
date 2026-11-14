package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which apps in front of a game are the emulator's own, as read off each product's instance on 2026-11-12. */
class EmulatorOwnAppsTest {

    @Test
    void eachProductsLauncherStoreAndAdsAreItsOwn() {
        for (String app : new String[]{"com.ldmnq.launcher3", "com.android.ld.appstore", "com.uncube.launcher3",
                "com.uncube.gamevantage", "com.bluestacks.gamecenter", "gg.now.ads.service", "app.lawnchair",
                "com.mumu.store", "com.microvirt.launcher2", "com.android.launcher3"}) {
            assertTrue(EmulatorOwnApps.covers(app, Optional.empty()), app);
        }
    }

    @Test
    void theHomeAppIsTheEmulatorsOwnWhateverItsName() {
        assertTrue(EmulatorOwnApps.covers("com.tencent.launcher", Optional.of("com.tencent.launcher")));
    }

    @Test
    void googleAndTheBrowserAreTheGamesBusiness() {
        for (String app : new String[]{"com.google.android.gms", "com.android.vending", "com.android.chrome",
                "com.android.permissioncontroller", "com.zjcs.android.us", ""}) {
            assertFalse(EmulatorOwnApps.covers(app, Optional.of("com.ldmnq.launcher3")), app);
        }
        assertFalse(EmulatorOwnApps.covers(null, Optional.empty()));
    }

    /**
     * LDPlayer's address answered by MSI App Player (live 2026-11-14: BlueStacks and LDPlayer both take
     * 127.0.0.1:5555) is not LDPlayer; its own packages, or none to tell by, are.
     */
    @Test
    void anotherProductsAndroidOnTheAddressIsNotThisInstance() {
        EmulatorInstance ld = new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", AdbEndpoint.loopback(5555));
        List<String> msi = List.of("android", "com.google.android.gms", "com.uncube.launcher3");
        String why = EmulatorOwnApps.notThis(ld, msi).orElseThrow();
        assertTrue(why.contains("127.0.0.1:5555") && why.contains("BlueStacks") && why.contains("LDPlayer"), why);
        assertTrue(EmulatorOwnApps.notThis(ld, List.of("com.ldmnq.launcher3", "android")).isEmpty());
        assertTrue(EmulatorOwnApps.notThis(ld, List.of("android")).isEmpty(), "nothing to tell by");
        assertTrue(EmulatorOwnApps.notThis(ld, List.of("com.ldmnq.launcher3", "com.uncube.launcher3")).isEmpty(),
                "two products' apps tell nothing");
        EmulatorInstance phone = new EmulatorInstance(PlatformId.PHYSICAL, "Phone", AdbEndpoint.loopback(5555));
        assertTrue(EmulatorOwnApps.notThis(phone, msi).isEmpty(), "a product without apps of its own is not checked");
        assertEquals(Optional.of(PlatformId.MUMU), EmulatorOwnApps.productOf(List.of("com.netease.mumu.cloner")));
    }

    @Test
    void aVendorsNameIsMatchedWholeNotAsTheStartOfAWord() {
        assertTrue(EmulatorOwnApps.covers("com.netease.mumu.cloner", Optional.empty()));
        assertFalse(EmulatorOwnApps.covers("com.netease.mumuxyz", Optional.empty()));
        assertFalse(EmulatorOwnApps.covers("com.android.launcherfoo", Optional.empty()));
        assertFalse(EmulatorOwnApps.covers("app.lawnchairs.game", Optional.empty()));
    }
}
