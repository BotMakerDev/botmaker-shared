package com.botmaker.shared.emulator;

import com.botmaker.shared.emulator.WaydroidDiagnostics.Finding;
import com.botmaker.shared.emulator.WaydroidDiagnostics.Issue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diagnostics probes, each driven by the text it examines rather than by a live Waydroid.
 *
 * <p>What these mostly pin down is the <em>negative</em> half: a probe that always fires is worse than no
 * probe, because it teaches the user to ignore the panel. So every case here that should stay silent is
 * asserted silent — an unreadable file, a missing systemd, a resolution nobody asked about.
 */
class WaydroidDiagnosticsTest {

    /** The real {@code [properties]} block from a box where libhoudini is installed. */
    private static final String CONFIG_WITH_BRIDGE = """
            [waydroid]
            arch = x86_64
            vendor_type = MAINLINE

            [properties]
            ro.dalvik.vm.native.bridge = libhoudini.so
            ro.enable.native.bridge.exec = 1
            ro.dalvik.vm.isa.arm = x86
            """;

    private static final String CONFIG_WITHOUT_BRIDGE = """
            [waydroid]
            arch = x86_64
            vendor_type = MAINLINE

            [properties]
            """;

    @Test
    void anInstalledNativeBridgeIsNotReported() {
        // This is the case on the development box, and the check that the probe isn't simply always-on.
        assertNull(WaydroidDiagnostics.noNativeBridge(CONFIG_WITH_BRIDGE));
    }

    @Test
    void aMissingNativeBridgeIsReportedWithTheScriptRecipe() {
        Finding finding = WaydroidDiagnostics.noNativeBridge(CONFIG_WITHOUT_BRIDGE);
        assertNotNull(finding);
        assertEquals(Issue.NO_NATIVE_BRIDGE, finding.issue());
        assertTrue(finding.commandBlock().contains("main.py install libhoudini"));
        assertFalse(finding.selfFixable(), "it rewrites the system image — BotMaker must not run it");
    }

    @Test
    void anUnreadableConfigIsNotAFinding() {
        // The file is root-readable on some setups. Reporting "no native bridge" because we could not look
        // would send the user to reinstall a translation layer they already have.
        assertNull(WaydroidDiagnostics.noNativeBridge(null));
    }

    @Test
    void forwardingOffIsReportedWithTheInterfaceFromTheDefaultRoute() {
        Finding finding = WaydroidDiagnostics.noInternet("0\n", WaydroidStatus.parse(""), "wlp3s0");
        assertNotNull(finding);
        assertEquals(Issue.NO_INTERNET, finding.issue());
        assertTrue(finding.commandBlock().contains("oifname \"wlp3s0\" masquerade"));
        assertTrue(finding.commandBlock().contains("net.ipv4.ip_forward=1"));
        // ufw has to come down for the rule and back up afterwards; dropping either half was the trap here.
        assertTrue(finding.commandBlock().contains("sudo ufw disable"));
        assertTrue(finding.commandBlock().contains("sudo ufw enable"));
    }

    @Test
    void forwardingOnIsSilentAndAnUnknownInterfaceIsStillActionable() {
        assertNull(WaydroidDiagnostics.noInternet("1\n", null, "eth0"));
        assertNull(WaydroidDiagnostics.noInternet(null, null, "eth0"), "unreadable /proc is not a diagnosis");
        Finding finding = WaydroidDiagnostics.noInternet("0", null, null);
        assertTrue(finding.commandBlock().contains("<your-default-interface>"),
                "a placeholder the user can see and fill beats a rule that silently NATs the wrong link");
    }

    @Test
    void theDefaultRouteInterfaceIsReadOutOfIpRoute() {
        String ipRoute = """
                default via 192.168.1.1 dev wlp3s0 proto dhcp src 192.168.1.42 metric 600
                192.168.240.0/24 dev waydroid0 proto kernel scope link src 192.168.240.1""";
        assertEquals("wlp3s0", WaydroidDiagnostics.defaultInterface(ipRoute));
        assertNull(WaydroidDiagnostics.defaultInterface("192.168.240.0/24 dev waydroid0 scope link"));
        assertNull(WaydroidDiagnostics.defaultInterface(null));
    }

    @Test
    void aStoppedContainerIsReportedButAMissingSystemctlIsNot() {
        assertNotNull(WaydroidDiagnostics.containerDown("inactive"));
        assertNull(WaydroidDiagnostics.containerDown("active"));
        // `is-active` on a running-but-degraded unit prints "activating"/"active (exited)" — the prefix match
        // keeps those out of the panel.
        assertNull(WaydroidDiagnostics.containerDown("active (exited)"));
        assertNull(WaydroidDiagnostics.containerDown(null), "no systemd is not a broken container");
        assertNull(WaydroidDiagnostics.containerDown(""));
    }

    @Test
    void aStoppedSessionIsOfferedRatherThanAlarming() {
        assertNotNull(WaydroidDiagnostics.sessionStopped(WaydroidStatus.parse("Session:\tSTOPPED")));
        assertNull(WaydroidDiagnostics.sessionStopped(WaydroidStatus.parse("Session:\tRUNNING")));
    }

    @Test
    void aResolutionMismatchIsTheOneThingBotMakerMayFixItself() {
        WaydroidResolution actual = new WaydroidResolution(1280, 720);
        WaydroidResolution wanted = new WaydroidResolution(1080, 1920);
        Finding finding = WaydroidDiagnostics.resolutionMismatch(actual, wanted);
        assertNotNull(finding);
        assertTrue(finding.selfFixable(), "no root, and entirely inside Waydroid's own configuration");
        assertTrue(finding.commandBlock().contains("persist.waydroid.width 1080"));

        assertNull(WaydroidDiagnostics.resolutionMismatch(wanted, wanted));
        assertNull(WaydroidDiagnostics.resolutionMismatch(actual, null), "nobody asked for a size");
        assertNull(WaydroidDiagnostics.resolutionMismatch(null, wanted),
                "unset means Waydroid's own default — not a mismatch we can assert");
    }
}
