package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.WaydroidResolution;
import com.botmaker.shared.emulator.WaydroidStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Waydroid checks, each driven by the text it examines rather than by a live Waydroid. A check that always
 * fails teaches the user to ignore the list, so every case that should pass is asserted to pass.
 */
class WaydroidChecksTest {

    private static final String AMD = "processor\t: 0\nvendor_id\t: AuthenticAMD\n";
    private static final String INTEL = "processor\t: 0\nvendor_id\t: GenuineIntel\n";

    /** This machine's config on 2026-11-01. */
    private static final String HOUDINI = """
            [waydroid]
            arch = x86_64
            mount_overlays = True
            binder_protocol = aidl3
            system_ota = https://ota.waydro.id/system/lineage/waydroid_x86_64/GAPPS.json

            [properties]
            ro.product.cpu.abilist = x86_64,x86,arm64-v8a,armeabi-v7a,armeabi
            ro.dalvik.vm.native.bridge = libhoudini.so
            ro.enable.native.bridge.exec = 1
            """;

    private static final String NDK = HOUDINI.replace("libhoudini.so", "libndk_translation.so");
    private static final String NO_BRIDGE = HOUDINI.replace("ro.dalvik.vm.native.bridge = libhoudini.so\n", "");

    @Test
    void libhoudiniOnAnAmdProcessorIsFixedToLibndk() {
        CheckResult row = WaydroidChecks.translator(AMD, HOUDINI, "13");
        assertEquals(CheckState.FAILED, row.state());
        assertTrue(row.fixable());
        assertTrue(row.reason().contains("libhoudini on an AMD processor"), row.reason());
        assertTrue(row.remedy().contains("Undo"));
    }

    @Test
    void theSuitedTranslatorPasses() {
        assertEquals(CheckState.OK, WaydroidChecks.translator(AMD, NDK, "13").state());
        assertEquals(CheckState.OK, WaydroidChecks.translator(INTEL, HOUDINI, "13").state());
        assertEquals(CheckState.OK, WaydroidChecks.translator("", HOUDINI, "13").state(),
                "an unknown processor with a translator is not asserted wrong");
    }

    @Test
    void noTranslatorIsFixable() {
        CheckResult row = WaydroidChecks.translator(AMD, NO_BRIDGE, "11");
        assertTrue(row.fixable());
        assertTrue(row.reason().startsWith("No ARM translator"));
    }

    @Test
    void anAndroidWithNoPinnedTranslatorSaysSoAndRunsNothing() {
        CheckResult row = WaydroidChecks.translator(AMD, HOUDINI, "12");
        assertEquals(CheckState.FAILED, row.state());
        assertFalse(row.fixable());
        assertTrue(row.remedy().contains("no pinned translator for Android 12"), row.remedy());
        assertTrue(row.commandBlock().contains("main.py install libndk"));
    }

    @Test
    void overlaysOffLeaveNoFix() {
        CheckResult row = WaydroidChecks.translator(AMD, HOUDINI.replace("mount_overlays = True",
                "mount_overlays = False"), "13");
        assertFalse(row.fixable());
        assertTrue(row.remedy().contains("overlays are off"), row.remedy());
    }

    @Test
    void androidsVersionComesFromTheBinderProtocolWhileWaydroidIsStopped() {
        assertEquals("13", WaydroidChecks.androidRelease("[12:00] WayDroid session is stopped", HOUDINI));
        assertEquals("11", WaydroidChecks.androidRelease(null, HOUDINI.replace("aidl3", "aidl2")));
        assertEquals("12", WaydroidChecks.androidRelease("12", HOUDINI));
        assertNull(WaydroidChecks.androidRelease(null, null));
        assertTrue(WaydroidChecks.translator(AMD, HOUDINI, null).fixable());
    }

    @Test
    void googlePlayIsReadFromTheImagesChannel() {
        assertEquals(CheckState.OK, WaydroidChecks.googlePlay(WaydroidChecks.hasPlay(HOUDINI)).state());
        CheckResult vanilla = WaydroidChecks.googlePlay(WaydroidChecks.hasPlay(HOUDINI.replace("GAPPS", "VANILLA")));
        assertTrue(vanilla.failed() && vanilla.fixable());
        assertEquals(CheckState.INFO, WaydroidChecks.googlePlay(WaydroidChecks.hasPlay("[waydroid]\n")).state());
    }

    @Test
    void missingWaydroidLinksItsInstallPageAndLeavesTheRestAfterIt() {
        CheckResult row = WaydroidChecks.installed(false, false);
        assertFalse(row.fixable());
        assertEquals(WaydroidChecks.INSTALL_PAGE, row.link());
        List<CheckResult> rows = WaydroidChecks.after(List.of(row));
        assertEquals(List.of(EmulatorCheck.WAYDROID_INSTALLED, EmulatorCheck.WAYDROID_RUNNING,
                EmulatorCheck.ADB_BOOTED, EmulatorCheck.INTERNET, EmulatorCheck.GOOGLE_PLAY,
                EmulatorCheck.ARM_TRANSLATOR, EmulatorCheck.DO_NOT_DISTURB),
                rows.stream().map(CheckResult::check).toList());
        assertEquals(CheckState.AFTER_ABOVE, rows.get(1).state());
        assertEquals(CheckState.INFO, rows.getLast().state());
        assertTrue(WaydroidChecks.installed(true, false).commandBlock().contains("waydroid init"));
        assertEquals(CheckState.OK, WaydroidChecks.installed(true, true).state());
    }

    @Test
    void aStoppedContainerIsFixableButAMissingSystemctlIsNotAFailure() {
        assertTrue(WaydroidChecks.running("inactive", null, null).fixable());
        assertEquals(CheckState.OK, WaydroidChecks.running("active", null, null).state());
        assertEquals(CheckState.OK, WaydroidChecks.running("active (exited)", null, null).state());
        assertEquals(CheckState.OK, WaydroidChecks.running(null, null, null).state(), "no systemd");
        assertTrue(WaydroidChecks.running("active", null, WaydroidStatus.parse("Session:\tSTOPPED")).fixable());
        assertEquals(CheckState.OK,
                WaydroidChecks.running("active", null, WaydroidStatus.parse("Session:\tRUNNING")).state());
        assertEquals(CheckState.OK, WaydroidChecks.running("active", null, WaydroidStatus.parse(null)).state(),
                "waydroid status not answering is no reading");
        assertEquals(CheckState.OK, WaydroidChecks.running("Failed to connect to bus", null, null).state());
    }

    @Test
    void anArmAndroidNeedsNoTranslator() {
        assertEquals(CheckState.OK, WaydroidChecks.translator(null,
                NO_BRIDGE.replace("arch = x86_64", "arch = arm64"), "13").state());
    }

    @Test
    void aServiceTheSystemWontRunAsksForLabelsNotARestart() {
        String journal = "waydroid-container.service: Failed to execute /usr/bin/waydroid: Permission denied\n";
        CheckResult row = WaydroidChecks.running("failed", journal, null);
        assertFalse(row.fixable());
        assertTrue(row.reason().contains("/usr/bin/waydroid"));
        assertTrue(row.commandBlock().startsWith("sudo restorecon"), "a restart alone fails again");
    }

    @Test
    void forwardingOffIsFixableThroughTheDefaultRoutesInterface() {
        String ipRoute = """
                default via 192.168.1.1 dev wlp3s0 proto dhcp src 192.168.1.42 metric 600
                192.168.240.0/24 dev waydroid0 proto kernel scope link src 192.168.240.1""";
        CheckResult row = WaydroidChecks.internet("0\n", ipRoute);
        assertTrue(row.fixable());
        assertTrue(row.commandBlock().contains("oifname \"wlp3s0\" masquerade"));
        assertTrue(row.commandBlock().contains("ufw disable") && row.commandBlock().contains("ufw enable"),
                "the firewall comes down for the rule and back up after");
        CheckResult noRoute = WaydroidChecks.internet("0", null);
        assertFalse(noRoute.fixable());
        assertTrue(noRoute.commandBlock().contains("<your-default-interface>"));
        assertEquals(CheckState.OK, WaydroidChecks.internet("1\n", ipRoute).state());
        assertEquals(CheckState.OK, WaydroidChecks.internet(null, ipRoute).state(), "unreadable /proc");
        assertEquals("wlp3s0", WaydroidChecks.defaultInterface(ipRoute));
        assertNull(WaydroidChecks.defaultInterface("192.168.240.0/24 dev waydroid0 scope link"));
    }

    @Test
    void aSizeMismatchIsFixedWithoutAPassword() {
        WaydroidResolution actual = new WaydroidResolution(1280, 720);
        WaydroidResolution wanted = new WaydroidResolution(1080, 1920);
        CheckResult row = WaydroidChecks.resolution(actual, wanted);
        assertTrue(row.fixable());
        assertTrue(row.remedy().contains("no password"));
        assertEquals(CheckState.OK, WaydroidChecks.resolution(wanted, wanted).state());
        assertNull(WaydroidChecks.resolution(actual, null), "nobody asked for a size");
        assertNull(WaydroidChecks.resolution(null, wanted), "Waydroid's own default");
    }

    @Test
    void anIdOutsideTheSetIsUnknown() {
        assertEquals(EmulatorCheck.UNKNOWN, EmulatorCheck.fromId("nope"));
        assertEquals(EmulatorCheck.ARM_TRANSLATOR, EmulatorCheck.fromId("arm_translator"));
        assertEquals(Translator.NONE, Translator.ofBridgeProp("0"));
        assertEquals(Translator.UNKNOWN, Translator.ofBridgeProp("libfoo.so"));
        assertEquals(CpuVendor.UNKNOWN, CpuVendor.of(null));
    }
}
