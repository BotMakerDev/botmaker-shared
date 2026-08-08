package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address half of the phone work: that a {@link AdbEndpoint.Server} is a first-class address rather than
 * a degenerate {@code ""}/{@code 0} host-port pair, and that {@link EmulatorInstance#identity()} still tells
 * two instances apart once the pair is gone.
 */
class AdbEndpointTest {

    @Test
    void tcpLabelIsTheHostAndPort() {
        assertEquals("127.0.0.1:5555", new AdbEndpoint.Tcp("127.0.0.1", 5555).label());
        assertEquals("192.168.1.5:5037", new AdbEndpoint.Tcp("  192.168.1.5  ", 5037).label());
    }

    @Test
    void serialIsTheLabelForAServerDevice() {
        assertEquals("R5CT30ABCDE", new AdbEndpoint.Server("R5CT30ABCDE").label());
        // A networked device is named ip:port by the adb server; that naming is passed through untouched.
        assertEquals("192.168.1.5:5555", new AdbEndpoint.Server("192.168.1.5:5555").label());
    }

    /**
     * The whole reason this type exists: a USB serial has no host and no port, and the old pair could only
     * write it down as something a socket probe would call dead.
     */
    @Test
    void aSerialIsNotEqualToATcpAddressThatLooksLikeIt() {
        assertNotEquals(new AdbEndpoint.Server("192.168.1.5:5555").getClass(),
                new AdbEndpoint.Tcp("192.168.1.5", 5555).getClass());
        assertEquals("192.168.1.5:5555", new AdbEndpoint.Server("192.168.1.5:5555").label());
    }

    @Test
    void identityKeepsInstancesApartAcrossBothVariants() {
        EmulatorInstance phone = new EmulatorInstance(PlatformId.PHYSICAL, "Pixel 7",
                new AdbEndpoint.Server("R5CT30ABCDE"));
        EmulatorInstance mumu = new EmulatorInstance(PlatformId.MUMU, "Android", "127.0.0.1", 16384);

        assertEquals("device@R5CT30ABCDE", phone.identity());
        assertEquals("mumu@127.0.0.1:16384", mumu.identity());
        assertNotEquals(phone.identity(), mumu.identity());
    }

    /** An unroutable address answers "not reachable" rather than throwing out of a picker's row. */
    @Test
    void anUnreachableTcpAddressIsFalseNotAThrow() {
        assertFalse(new AdbEndpoint.Tcp("127.0.0.1", 1).reachable());
    }

    /** With no adb server running, a serial is simply not reachable — never an exception. */
    @Test
    void anUnknownSerialIsNotReachable() {
        assertFalse(new AdbEndpoint.Server("no-such-device-serial").reachable());
    }

    /**
     * {@code local()} decides which screen grab {@link AdbDevice#screencap()} takes, so a wrong answer is a
     * 10 MB framebuffer over a radio (or a needless PNG encode). Every emulator this stack discovers is on
     * loopback and must say so.
     */
    @Test
    void loopbackAddressesAreLocal() {
        assertTrue(new AdbEndpoint.Tcp("127.0.0.1", 5555).local());
        assertTrue(new AdbEndpoint.Tcp("localhost", 5555).local());
        assertTrue(new AdbEndpoint.Tcp("::1", 5555).local());
        assertTrue(AdbEndpoint.loopback(5555).local());
    }

    /** A phone is never local, whichever way it is attached — including a serial that spells an address. */
    @Test
    void phonesAreNotLocal() {
        assertFalse(new AdbEndpoint.Tcp("192.168.1.5", 5555).local());
        assertFalse(new AdbEndpoint.Server("R5CT30ABCDE").local());
        assertFalse(new AdbEndpoint.Server("127.0.0.1:5555").local());
    }

    @Test
    void instanceExposesTheEndpointItWasBuiltWith() {
        EmulatorInstance viaPair = new EmulatorInstance(PlatformId.LDPLAYER, "LD", "127.0.0.1", 5555);
        assertEquals(new AdbEndpoint.Tcp("127.0.0.1", 5555), viaPair.adb());
        assertEquals("127.0.0.1:5555", viaPair.endpoint());
    }
}
