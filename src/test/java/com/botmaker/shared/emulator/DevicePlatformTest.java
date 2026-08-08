package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configured-address half of {@link DevicePlatform} (the adb-server half needs a server, and is asserted
 * at the parse level in {@link AdbToolsTest}), plus the de-duplication rule in {@link Platforms}.
 */
class DevicePlatformTest {

    @Test
    void parsesAnAddressList() {
        List<EmulatorInstance> instances = DevicePlatform.configured("192.168.1.5:5555, 10.0.0.9:5556");
        assertEquals(2, instances.size());
        assertEquals("192.168.1.5:5555", instances.get(0).endpoint());
        assertEquals("10.0.0.9:5556", instances.get(1).endpoint());
        assertEquals(PlatformId.PHYSICAL, instances.get(0).platformId());
    }

    /** A phone is not something we can start or stop, and the instance says so rather than pretending. */
    @Test
    void aConfiguredPhoneHasNoLaunchOrStopCommand() {
        EmulatorInstance phone = DevicePlatform.configured("192.168.1.5:5555").get(0);
        assertTrue(phone.launchCommand().isEmpty());
        assertTrue(phone.stopCommand().isEmpty());
        assertEquals(false, phone.canLaunch());
    }

    /**
     * This value is hand-typed into a property or an environment variable, so one bad entry must not cost the
     * user the good ones next to it.
     */
    @Test
    void malformedEntriesAreSkippedRatherThanThrowing() {
        List<EmulatorInstance> instances =
                DevicePlatform.configured("nonsense, 192.168.1.5:notaport, :5555, 10.0.0.9:5555, 10.0.0.9:");
        assertEquals(1, instances.size());
        assertEquals("10.0.0.9:5555", instances.get(0).endpoint());
    }

    @Test
    void anEmptyOrMissingListIsEmpty() {
        assertEquals(0, DevicePlatform.configured("").size());
        assertEquals(0, DevicePlatform.configured(null).size());
        assertEquals(0, DevicePlatform.configured("   ").size());
    }

    @Test
    void portsOutsideTheValidRangeAreRejected() {
        assertEquals(0, DevicePlatform.configured("10.0.0.9:0").size());
        assertEquals(0, DevicePlatform.configured("10.0.0.9:70000").size());
    }

    /**
     * One device, two reporters. A Waydroid container the user has also run {@code adb connect} against shows
     * up under its own product <em>and</em> in the adb server's list, at the identical address — and with two
     * different identities, so nothing downstream could have noticed.
     */
    @Test
    void discoveryKeepsTheSpecificProductWhenAnAddressIsReportedTwice() {
        EmulatorInstance waydroid =
                new EmulatorInstance(PlatformId.WAYDROID, "Waydroid", "192.168.240.112", 5555);
        EmulatorInstance alsoSeenByTheServer = new EmulatorInstance(PlatformId.PHYSICAL, "Waydroid device",
                new AdbEndpoint.Server("192.168.240.112:5555"));
        EmulatorInstance phone = new EmulatorInstance(PlatformId.PHYSICAL, "Pixel 7",
                new AdbEndpoint.Server("R5CT30ABCDE"));

        List<EmulatorInstance> deduped = Platforms.dedupe(List.of(waydroid, alsoSeenByTheServer, phone));

        assertEquals(2, deduped.size());
        assertEquals(PlatformId.WAYDROID, deduped.get(0).platformId(),
                "the product that knows how to launch and stop it wins");
        assertEquals("R5CT30ABCDE", deduped.get(1).endpoint());
    }

    /** Discovery is total: with no server and no configured addresses it is simply empty. */
    @Test
    void discoveryNeverThrows() {
        assertTrue(new DevicePlatform().discover().size() >= 0);
    }
}
