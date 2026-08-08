package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code host:devices-l} parsing — the one part of the adb-server route that can be asserted without an adb
 * server, a cable or a phone. The sample lines are real output shapes, and each row here is a case that
 * changes what a user sees in a picker.
 */
class AdbToolsTest {

    private static final String DEVICES_L = """
            List of devices attached
            R5CT30ABCDE            device usb:1-4 product:x1q model:SM_G981B device:x1q transport_id:1
            emulator-5554          device product:sdk_gphone64 model:sdk_gphone64 device:emu64x transport_id:2
            192.168.1.5:5555       device product:panther model:Pixel_7 device:panther transport_id:3
            0A1B2C3D               unauthorized usb:1-2 transport_id:4
            """;

    @Test
    void parsesSerialStateModelAndTransport() {
        List<AdbTools.ServerDevice> devices = AdbTools.parseDevices(DEVICES_L);
        assertEquals(4, devices.size());

        AdbTools.ServerDevice usb = devices.get(0);
        assertEquals("R5CT30ABCDE", usb.serial());
        assertEquals("SM_G981B", usb.model());
        assertTrue(usb.usb());
        assertTrue(usb.online());
        assertFalse(usb.emulator());
    }

    /** The header line is not a device, and neither is a blank one. */
    @Test
    void skipsTheHeaderAndBlankLines() {
        assertEquals(0, AdbTools.parseDevices("List of devices attached\n\n\n").size());
        assertEquals(0, AdbTools.parseDevices("").size());
        assertEquals(0, AdbTools.parseDevices(null).size());
    }

    /**
     * The filter that decides whether a user sees one phone or two: an {@code emulator-*} serial is a product
     * {@link Platforms} already discovers for itself, so {@link DevicePlatform} must leave it alone.
     */
    @Test
    void emulatorSerialsAreRecognisedAsSuch() {
        List<AdbTools.ServerDevice> devices = AdbTools.parseDevices(DEVICES_L);
        assertTrue(devices.get(1).emulator());
        assertFalse(devices.get(2).emulator(), "a networked phone is ip:port, not emulator-*");
    }

    /**
     * An unaccepted "Allow USB debugging?" prompt is the most common reason a phone doesn't appear, so the
     * row is kept and reported as not-online rather than silently dropped.
     */
    @Test
    void unauthorizedDevicesSurviveParsingButAreNotOnline() {
        AdbTools.ServerDevice pending = AdbTools.parseDevices(DEVICES_L).get(3);
        assertEquals("0A1B2C3D", pending.serial());
        assertEquals("unauthorized", pending.state());
        assertFalse(pending.online());
    }

    @Test
    void displayNameFallsBackToTheSerialAndUnderscoresBecomeSpaces() {
        List<AdbTools.ServerDevice> devices = AdbTools.parseDevices(DEVICES_L);
        assertEquals("Pixel 7", devices.get(2).displayName());
        assertEquals("0A1B2C3D", devices.get(3).displayName(), "no model reported → the serial");
    }

    /** A truncated or unexpected line is skipped, never a throw — this parses another program's output. */
    @Test
    void malformedLinesAreSkipped() {
        assertEquals(0, AdbTools.parseDevices("justaserial\n").size());
    }
}
