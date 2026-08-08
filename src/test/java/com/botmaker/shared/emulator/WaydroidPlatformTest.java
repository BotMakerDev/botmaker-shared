package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Waydroid's status parsing, resolution properties and launch argv — all pure, so they run on a machine with
 * no Waydroid (which is every CI machine and most dev boxes).
 */
class WaydroidPlatformTest {

    /** Real output from a live box, tabs and all. */
    private static final String STOPPED = "Session:\tSTOPPED\nVendor type:\tMAINLINE";

    private static final String RUNNING = """
            Session:\tRUNNING
            Container:\tRUNNING
            Vendor type:\tMAINLINE
            Session user:\tbenjamin(1000)
            IP address:\t192.168.240.112""";

    @Test
    void aStoppedSessionParsesAndStillYieldsAnAddressToConnectTo() {
        WaydroidStatus status = WaydroidStatus.parse(STOPPED);
        assertFalse(status.sessionRunning());
        assertFalse(status.containerRunning(), "a stopped session prints no Container line at all");
        assertEquals("MAINLINE", status.vendorType());
        // The address is only reported while running, so discovery has to have somewhere to point before then.
        assertEquals(WaydroidStatus.DEFAULT_IP, status.ipAddress());
    }

    @Test
    void aRunningSessionReportsItsOwnAddress() {
        WaydroidStatus status = WaydroidStatus.parse(RUNNING);
        assertTrue(status.sessionRunning());
        assertTrue(status.containerRunning());
        assertEquals("192.168.240.112", status.ipAddress());
    }

    @Test
    void unreadableStatusIsNotRunningRatherThanAnError() {
        // "could not tell" and "not running" lead to the same next move, so they are not distinguished.
        assertFalse(WaydroidStatus.parse(null).sessionRunning());
        assertFalse(WaydroidStatus.parse("waydroid: command not found").sessionRunning());
        assertEquals(WaydroidStatus.DEFAULT_IP, WaydroidStatus.parse("").ipAddress());
    }

    @Test
    void anUnreadableResolutionIsNullRatherThanAFabricatedOne() {
        // `waydroid prop get` prints an empty line for an unset property — and, with the session down, the
        // banner below instead. Neither is a size, and neither may be mistaken for one: what the *caller* does
        // with the null is where the sizing decision belongs (see the gamescope tests).
        assertNull(WaydroidResolution.parse("", ""));
        assertNull(WaydroidResolution.parse("WayDroid session is stopped", "WayDroid session is stopped"));
        assertNull(WaydroidResolution.parse("1080", ""));
        assertNull(WaydroidResolution.parse("1080", "not-a-number"));
        assertNull(WaydroidResolution.parse("0", "1920"));
        assertEquals(new WaydroidResolution(1080, 1920), WaydroidResolution.parse(" 1080 \n", "1920"));
        assertThrows(IllegalArgumentException.class, () -> new WaydroidResolution(1080, -1));
    }

    @Test
    void theLaunchArgvIsTheVerifiedGamescopeOneLiner() {
        assertEquals(List.of("gamescope", "-W", "1080", "-H", "1920", "-w", "1080", "-h", "1920",
                        "--expose-wayland", "waydroid", "show-full-ui"),
                WaydroidPlatform.launchCommand(new WaydroidResolution(1080, 1920), true));
    }

    @Test
    void anUnknownResolutionIsStillSized() {
        // The regression this guards: `prop get` cannot answer with the container down, which is the only
        // moment this argv is built — so "unknown" was the normal case, the flags were dropped, and gamescope
        // scaled Android's framebuffer into its own default output. Templates cropped from that window then
        // matched nothing in the ADB frame the bot compares against.
        String w = Integer.toString(WaydroidResolution.DEFAULT.width());
        String h = Integer.toString(WaydroidResolution.DEFAULT.height());
        assertEquals(List.of("gamescope", "-W", w, "-H", h, "-w", w, "-h", h,
                        "--expose-wayland", "waydroid", "show-full-ui"),
                WaydroidPlatform.launchCommand(null, true));
    }

    @Test
    void theOutputAndInternalSizesAreAlwaysEqual() {
        // -W/-H differing from -w/-h *is* the scaler. Assert the shape rather than trusting the literal above.
        List<String> argv = WaydroidPlatform.launchCommand(new WaydroidResolution(1440, 900), true);
        assertEquals(argv.get(argv.indexOf("-W") + 1), argv.get(argv.indexOf("-w") + 1));
        assertEquals(argv.get(argv.indexOf("-H") + 1), argv.get(argv.indexOf("-h") + 1));
    }

    @Test
    void anAdministratorCanPinTheSizeInWaydroidsOwnConfig() {
        // Real waydroid.cfg shape, trimmed: the size is optional and absent by default, and the [waydroid]
        // section carries keys that must not be mistaken for it.
        String cfg = """
                [waydroid]
                arch = x86_64
                vendor_type = MAINLINE

                [properties]
                ro.product.cpu.abilist = x86_64,x86
                persist.waydroid.width = 1080
                persist.waydroid.height = 1920""";
        assertEquals(new WaydroidResolution(1080, 1920), WaydroidResolution.fromSystemConfig(cfg));
        assertNull(WaydroidResolution.fromSystemConfig("[waydroid]\narch = x86_64\n\n[properties]\n"),
                "the stock file pins no size — that must read as unset, not as a parse failure");
        assertNull(WaydroidResolution.fromSystemConfig(
                        "[waydroid]\npersist.waydroid.width = 1080\npersist.waydroid.height = 1920"),
                "outside [properties] those keys mean nothing to Waydroid, so they must mean nothing here");
        assertNull(WaydroidResolution.fromSystemConfig(null), "an unreadable file is not an error");
    }

    @Test
    void withoutGamescopeItFallsBackToTheBareUiCommand() {
        // On a real Wayland desktop this works; on X11 it fails visibly, which beats offering no launch at all.
        assertEquals(List.of("waydroid", "show-full-ui"), WaydroidPlatform.launchCommand(null, false));
    }

    @Test
    void theInstanceIsNotOnLoopbackLikeEveryOtherPlatform() {
        // Waydroid's adbd is on the container's LXC address, not a 127.0.0.1 port forward. Getting this wrong
        // would silently connect to whatever else happens to be listening on 127.0.0.1:5555 — LDPlayer's
        // instance 0, for one.
        EmulatorInstance instance = new EmulatorInstance(PlatformId.WAYDROID, "Waydroid",
                WaydroidStatus.DEFAULT_IP, WaydroidStatus.ADB_PORT);
        assertEquals("192.168.240.112:5555", instance.endpoint());
        assertEquals("waydroid@192.168.240.112:5555", instance.identity());
        assertEquals("Waydroid", instance.brand());
    }
}
