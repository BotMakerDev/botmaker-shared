package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void parsePackageListEmptyOnBlank() {
        assertTrue(AdbDevice.parsePackageList("").isEmpty());
        assertTrue(AdbDevice.parsePackageList(null).isEmpty());
    }

    @Test
    void parsesForegroundPackageFromResumedActivity() {
        String dumpsys = "  mResumedActivity: ActivityRecord{1a2b3c u0 com.foo.game/.MainActivity t42}";
        assertEquals("com.foo.game", AdbDevice.parseForegroundPackage(dumpsys));
    }

    @Test
    void foregroundPackageEmptyWhenAbsent() {
        assertEquals("", AdbDevice.parseForegroundPackage("nothing interesting here"));
        assertEquals("", AdbDevice.parseForegroundPackage(""));
    }
}
