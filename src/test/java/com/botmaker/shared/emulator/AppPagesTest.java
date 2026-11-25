// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPagesTest {

    private static final String SETTINGS = "com.android.settings";

    @Test
    void theActivitiesAnIntentOpensAreTheResolverTablesOwn() throws IOException {
        List<AndroidPage.Activity> pages = AppPages.exported(SETTINGS, fixture("settings-package.txt"));
        assertEquals(List.of(
                new AndroidPage.Activity("com.android.settings/.Settings"),
                new AndroidPage.Activity("com.android.settings/.Settings$WifiSettingsActivity"),
                new AndroidPage.Activity("com.android.settings/.wifi.WifiSettings"),
                new AndroidPage.Activity("com.android.settings/.Settings$DateTimeSettingsActivity")), pages);
    }

    @Test
    void noDumpIsNoPage() {
        assertEquals(List.of(), AppPages.exported(SETTINGS, null));
        assertEquals(List.of(), AppPages.exported(SETTINGS, ""));
    }

    @Test
    void theSignInPageInFocusIsGooglesDoor() {
        String focus = "  mCurrentFocus=Window{1a2b u0 com.google.android.gms/"
                + "com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity}\n"
                + "  mFocusedApp=ActivityRecord{3c4d u0 com.google.android.gms/"
                + ".auth.uiflows.minutemaid.MinuteMaidActivity t12}";
        String component = AppPages.focused(focus).orElseThrow();
        assertEquals(new AppPages.Reached(new AndroidPage.Door(AndroidDoor.GOOGLE_SIGN_IN)),
                AppPages.openNow(component, List.of()));
    }

    @Test
    void anExportedActivityInFocusIsThatActivity() throws IOException {
        List<AndroidPage.Activity> exported = AppPages.exported(SETTINGS, fixture("settings-package.txt"));
        AppPages.Now now = AppPages.openNow("com.android.settings/com.android.settings.wifi.WifiSettings", exported);
        assertEquals(new AppPages.Reached(new AndroidPage.Activity("com.android.settings/.wifi.WifiSettings")), now);
    }

    @Test
    void aDoorWinsOverTheActivityItLandsOn() throws IOException {
        List<AndroidPage.Activity> exported = AppPages.exported(SETTINGS, fixture("settings-package.txt"));
        AppPages.Now now = AppPages.openNow("com.android.settings/.Settings$WifiSettingsActivity", exported);
        assertEquals(new AppPages.Reached(new AndroidPage.Door(AndroidDoor.WIFI)), now);
    }

    @Test
    void aPageNoIntentOpensIsUnreachableByItsName() throws IOException {
        List<AndroidPage.Activity> exported = AppPages.exported(SETTINGS, fixture("settings-package.txt"));
        AppPages.Now now = AppPages.openNow("com.android.settings/com.android.settings.SubSettings", exported);
        assertEquals(new AppPages.Unreachable("com.android.settings/.SubSettings"), now);
    }

    @Test
    void aPopupOverThePageLeavesTheFocusedApp() {
        String focus = "  mCurrentFocus=Window{1a2b u0 PopupWindow:9f8e}\n"
                + "  mFocusedApp=ActivityRecord{3c4d u0 com.android.settings/.wifi.WifiSettings t12}";
        assertEquals(Optional.of("com.android.settings/.wifi.WifiSettings"), AppPages.focused(focus));
    }

    @Test
    void noFocusIsNoComponent() {
        assertTrue(AppPages.focused("  mCurrentFocus=null\n  mFocusedApp=null").isEmpty());
        assertTrue(AppPages.focused(null).isEmpty());
    }

    @Test
    void aDoorLandsOnlyInItsOwnPackage() {
        assertTrue(AndroidDoor.GOOGLE_SIGN_IN.landsOn("com.google.android.gms", ".auth.MinuteMaidActivity"));
        assertEquals(false, AndroidDoor.GOOGLE_SIGN_IN.landsOn(SETTINGS, ".MinuteMaidActivity"));
        assertEquals(false, AndroidDoor.UNKNOWN.landsOn("", ""));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = AppPagesTest.class.getResourceAsStream("/dumpsys/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
