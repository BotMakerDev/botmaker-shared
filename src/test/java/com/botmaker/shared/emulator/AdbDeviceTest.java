package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AdbDevice}'s pure output parsers — {@code pm list packages} and the foreground-app
 * {@code dumpsys} scrape — exercised without a live ADB connection.
 */
class AdbDeviceTest {

    @Test
    void parsesPackageListDroppingThePackagePrefix() {
        String out = "package:com.foo.game\npackage:com.bar.app\n";
        assertEquals(List.of("com.foo.game", "com.bar.app"), AdbDevice.parsePackageList(out));
    }

    @Test
    void parsePackageListStripsTrailingPathAndDeDupes() {
        String out = "package:com.foo.game=/data/app/com.foo.game/base.apk\npackage:com.foo.game\n";
        assertEquals(List.of("com.foo.game"), AdbDevice.parsePackageList(out));
    }

    @Test
    void theInstalledVersionIsTheFirstVersionCodeAndNoneIsMinusOne() {
        String dumpsys = "Packages:\n  Package [com.foo.game] (1a2b):\n    versionCode=1203 minSdk=24 targetSdk=34\n"
                + "    versionName=1.2.3\n  Package [com.foo.game] (3c4d):\n    versionCode=1100 minSdk=24\n";
        assertEquals(1203, AdbDevice.parseVersionCode(dumpsys));
        assertEquals(-1, AdbDevice.parseVersionCode("Dexopt state:\n"));
        assertEquals(-1, AdbDevice.parseVersionCode(null));
    }

    @Test
    void parsePackageListEmptyOnBlank() {
        assertTrue(AdbDevice.parsePackageList("").isEmpty());
        assertTrue(AdbDevice.parsePackageList(null).isEmpty());
    }

    @Test
    void monkeyStoppedForWantOfKeysIsNoStart() {
        // Waydroid's whole stdout: the reason ("SYS_KEYS has no physical keys") went to stderr.
        assertFalse(AdbDevice.startedApp("  bash arg: -p\n  bash arg: com.foo.game\n  bash arg: -c\n"
                + "  bash arg: android.intent.category.LAUNCHER\n  bash arg: 1\n"));
        assertFalse(AdbDevice.startedApp("Events injected: 0"));
        assertTrue(AdbDevice.startedApp("Events injected: 1\n## Network stats: elapsed time=26ms"));
    }

    @Test
    void parsesForegroundPackageFromResumedActivity() {
        String dumpsys = "  mResumedActivity: ActivityRecord{1a2b3c u0 com.foo.game/.MainActivity t42}";
        assertEquals("com.foo.game", AdbDevice.parseForegroundPackage(dumpsys));
    }

    @Test
    void typedTextIsOneSingleQuotedShellWord() {
        assertEquals("'hello%sworld'", AdbDevice.inputTextArgument("hello world"));
        // Everything the device's sh would act on stays inside the quotes; a quote is closed, escaped, reopened.
        assertEquals("'a;b&c$(id)`x`'", AdbDevice.inputTextArgument("a;b&c$(id)`x`"));
        assertEquals("'it'\\''s'", AdbDevice.inputTextArgument("it's"));
    }

    @Test
    void typedTextKeepsOnlyWhatInputTextCanType() {
        assertEquals("'ab'", AdbDevice.inputTextArgument("a\nbé"));
        assertEquals("", AdbDevice.inputTextArgument("\n\t"));
        assertEquals("", AdbDevice.inputTextArgument(null));
    }

    @Test
    void focusedPackageReadsTheFocusedWindowThenTheFocusedApp() {
        assertEquals(Optional.of("com.android.permissioncontroller"), AdbDevice.parseFocusedPackage("""
                  mCurrentFocus=Window{9f8e u0 com.android.permissioncontroller/com.android.Grant}
                  mFocusedApp=ActivityRecord{3c4d u0 com.foo.game/.MainActivity t12}"""));
        assertEquals(Optional.of("com.foo.game"), AdbDevice.parseFocusedPackage("""
                  mCurrentFocus=Window{9f8e u0 PopupWindow:5a1}
                  mFocusedApp=ActivityRecord{3c4d u0 com.foo.game/.MainActivity t12}"""));
        assertEquals(Optional.empty(), AdbDevice.parseFocusedPackage("  mCurrentFocus=null\n  mFocusedApp=null"));
        assertEquals(Optional.empty(), AdbDevice.parseFocusedPackage(null));
    }

    @Test
    void foregroundPackageEmptyWhenAbsent() {
        assertEquals("", AdbDevice.parseForegroundPackage("nothing interesting here"));
        assertEquals("", AdbDevice.parseForegroundPackage(""));
    }
}
