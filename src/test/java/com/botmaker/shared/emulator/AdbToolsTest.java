package com.botmaker.shared.emulator;

import com.botmaker.shared.Executables;
import com.botmaker.shared.tools.ManagedTools;
import com.botmaker.shared.tools.UserDirs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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

    // --- finding a binary, and the managed one ---

    @AfterEach
    void clearOverride() {
        System.clearProperty(UserDirs.CACHE_PROPERTY);
    }

    private static String adbName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "adb.exe" : "adb";
    }

    /**
     * <b>The managed copy is the last resort, not the first choice.</b> With one installed, a real adb on
     * {@code PATH} must still win: an adb server is a singleton on port 5037, and a second binary quietly
     * displacing the one this machine's SDK and IDE already agree on is a worse failure than the missing-adb
     * one this fallback exists to fix.
     *
     * <p>Written to assert on whichever machine it runs: a dev box has an adb and takes the first branch, a CI
     * box has none and takes the second. Both are real cases, and {@code managed()} must agree with either.
     */
    @Test
    void aRealAdbBeatsTheManagedCopyAndTheManagedCopyBeatsNothing(@TempDir Path cache) throws Exception {
        System.setProperty(UserDirs.CACHE_PROPERTY, cache.toString());
        Path ours = cache.resolve("tools").resolve("platform-tools").resolve(adbName());
        Files.createDirectories(ours.getParent());
        Files.writeString(ours, "#!/bin/sh\n");
        assertTrue(ManagedTools.adb().isPresent(), "the managed copy is now installed");

        Optional<File> onPath = Executables.find(adbName());
        boolean sdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null;
        if (onPath.isPresent()) {
            assertEquals(onPath.get().getAbsolutePath(), AdbTools.binary().orElseThrow().getAbsolutePath());
            assertFalse(AdbTools.managed(), "PATH's adb is the user's, not ours");
        } else if (!sdk) {
            assertEquals(ours.toString(), AdbTools.binary().orElseThrow().getAbsolutePath());
            assertTrue(AdbTools.managed());
        }
    }

    /** With nothing downloaded, nothing is claimed — {@code managed()} must never be a guess. */
    @Test
    void nothingIsManagedUntilSomethingIsDownloaded(@TempDir Path cache) {
        System.setProperty(UserDirs.CACHE_PROPERTY, cache.toString());

        assertTrue(ManagedTools.adb().isEmpty());
        assertFalse(AdbTools.managed());
    }

    /**
     * Pairing with a blank field is refused before a process is started — the six-digit code is typed by hand
     * off a phone screen, so the empty case is the ordinary one, not the exotic one.
     */
    @Test
    void pairAndConnectRefuseBlankInputWithoutRunningAnything() {
        assertFalse(AdbTools.pair("", "123456").ok());
        assertFalse(AdbTools.pair("192.168.1.5:37000", " ").ok());
        assertFalse(AdbTools.pair(null, null).ok());
        assertFalse(AdbTools.connect("").ok());
        assertFalse(AdbTools.connect(null).ok());

        // Whatever the reason, it is a sentence a dialog can show as-is.
        assertFalse(AdbTools.connect(null).message().isBlank());
    }

    /** The hint stopped being an errand: it has to say BotMaker can fetch it, and what works meanwhile. */
    @Test
    void theInstallHintOffersTheDownloadAndNamesTheRouteThatNeedsNothing() {
        String hint = AdbTools.installHint();

        assertTrue(hint.contains("platform-tools"));
        assertTrue(hint.contains("download"), "a dead-end sentence is what this phase removed");
        assertTrue(hint.contains("tcpip"), "the route that needs no binary at all");
    }

    /** {@code adb mdns services} as adb 37 prints it, and as older builds did, with a dot after the type. */
    @Test
    void announcedServicesAreReadWithTheirKindAndAddress() {
        List<AdbTools.MdnsService> services = AdbTools.parseMdns("""
                List of discovered mdns services
                adb-R5CT30ABCDE-vWgJpq\t_adb-tls-connect._tcp\t192.168.1.5:41235
                botmaker-k3x9q2ma\t_adb-tls-pairing._tcp.\t192.168.1.5:37123
                adb-0A1B2C\t_adb._tcp\t192.168.1.9:5555
                printer\t_ipp._tcp\t192.168.1.2:631
                adb-broken\t_adb-tls-connect._tcp\tno-port
                Pixel 7\t_adb._tcp\t192.168.1.4:5555
                """);

        assertEquals(List.of(
                new AdbTools.MdnsService("adb-R5CT30ABCDE-vWgJpq", AdbTools.MdnsKind.CONNECT, "192.168.1.5:41235"),
                new AdbTools.MdnsService("botmaker-k3x9q2ma", AdbTools.MdnsKind.PAIRING, "192.168.1.5:37123"),
                new AdbTools.MdnsService("adb-0A1B2C", AdbTools.MdnsKind.LEGACY, "192.168.1.9:5555"),
                new AdbTools.MdnsService("printer", AdbTools.MdnsKind.UNKNOWN, "192.168.1.2:631"),
                new AdbTools.MdnsService("Pixel 7", AdbTools.MdnsKind.LEGACY, "192.168.1.4:5555")), services);
        assertEquals("R5CT30ABCDE", services.get(0).displayName());
        assertEquals("192.168.1.5", services.get(0).host());
        assertEquals("botmaker-k3x9q2ma", services.get(1).displayName());
        assertEquals(List.of(), AdbTools.parseMdns(""));
    }

    /** A phone adb already drives, by address or by the name adb's own mDNS connect gave it, is not offered. */
    @Test
    void onlyPhonesTheServerIsntConnectedToAreOfferedOnceEach() {
        var pixel = new AdbTools.MdnsService("adb-PIXEL-aaaaaa", AdbTools.MdnsKind.CONNECT, "192.168.1.5:41235");
        var galaxy = new AdbTools.MdnsService("adb-GALAXY-bbbbbb", AdbTools.MdnsKind.CONNECT, "192.168.1.6:40001");
        var tablet = new AdbTools.MdnsService("adb-TAB-cccccc", AdbTools.MdnsKind.LEGACY, "192.168.1.7:5555");
        var pairing = new AdbTools.MdnsService("botmaker-x", AdbTools.MdnsKind.PAIRING, "192.168.1.8:37000");
        var cabled = new AdbTools.MdnsService("adb-R5CT30-dddddd", AdbTools.MdnsKind.CONNECT, "192.168.1.9:40002");
        var devices = List.of(
                new AdbTools.ServerDevice("adb-PIXEL-aaaaaa._adb-tls-connect._tcp", "device", "Pixel_7", false),
                new AdbTools.ServerDevice("192.168.1.7:5555", "device", "", false),
                new AdbTools.ServerDevice("R5CT30", "device", "SM_G981B", true));

        assertEquals(List.of(galaxy),
                AdbTools.unconnected(List.of(pixel, galaxy, galaxy, tablet, pairing, cabled), devices),
                "a phone on the cable is the same phone as its announced serial");
    }

    /** The payload Android's QR scanner takes for adb, and a fresh, unescaped name and password each time. */
    @Test
    void aQrCodeCarriesTheAdbWifiPayloadWithFreshLettersAndDigits() {
        assertEquals("WIFI:T:ADB;S:botmaker-ab;P:123;;", new AdbTools.QrPairing("botmaker-ab", "123").payload());
        AdbTools.QrPairing one = AdbTools.QrPairing.random();
        AdbTools.QrPairing two = AdbTools.QrPairing.random();
        assertTrue(one.name().matches("botmaker-[a-z0-9]{8}"), one.name());
        assertTrue(one.password().matches("[a-z0-9]{10}"), one.password());
        assertFalse(one.equals(two));
    }

    @Test
    void anUnknownServiceTypeIsUnknownNeverAThrow() {
        assertEquals(AdbTools.MdnsKind.UNKNOWN, AdbTools.MdnsKind.fromId(null));
        assertEquals(AdbTools.MdnsKind.UNKNOWN, AdbTools.MdnsKind.fromId(""));
        assertEquals(AdbTools.MdnsKind.CONNECT, AdbTools.MdnsKind.fromId("_adb-tls-connect._tcp."));
    }
}
