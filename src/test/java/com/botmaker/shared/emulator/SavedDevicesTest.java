package com.botmaker.shared.emulator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saved-phone list: its text format, and the round-trip through a real file.
 *
 * <p>Every test that touches the disk points {@link SavedDevices#FILE_PROPERTY} at a temp directory first. A
 * test that wrote to the default location would be editing the list of phones on the machine running it —
 * which is the maintainer's own, and the file is user data rather than a cache it could rebuild.
 */
class SavedDevicesTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(SavedDevices.FILE_PROPERTY);
    }

    @Test
    void anAddressWithATabbedNameParses() {
        List<SavedDevices.SavedDevice> devices = SavedDevices.parse("192.168.1.5:5555\tPixel 7\n");

        assertEquals(1, devices.size());
        assertEquals("192.168.1.5", devices.get(0).host());
        assertEquals(5555, devices.get(0).port());
        assertEquals("Pixel 7", devices.get(0).name(), "a name may contain spaces, hence the tab");
    }

    /** A bare address is the common hand-written case, and falls back to showing the address. */
    @Test
    void abareAddressKeepsNoNameAndDisplaysItself() {
        SavedDevices.SavedDevice device = SavedDevices.parse("10.0.0.42:5555").get(0);

        assertEquals("", device.name());
        assertEquals("10.0.0.42:5555", device.displayName());
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        assertTrue(SavedDevices.parse("# a comment\n\n   \n").isEmpty());
    }

    /**
     * One malformed line must cost only itself. This file is hand-editable by design, so a typo in the middle
     * of it must not read as "this user has no phones".
     */
    @Test
    void aMalformedLineIsSkippedRatherThanLosingTheRest() {
        List<SavedDevices.SavedDevice> devices = SavedDevices.parse(String.join("\n",
                "192.168.1.5:5555",
                "not-an-address",
                "192.168.1.6:notaport",
                "192.168.1.7:",
                ":5555",
                "192.168.1.8:99999",
                "192.168.1.9:5555"));

        assertEquals(List.of("192.168.1.5:5555", "192.168.1.9:5555"),
                devices.stream().map(d -> d.endpoint().label()).toList());
    }

    /** The port is taken from the last colon, so a bracketed IPv6 literal keeps its own. */
    @Test
    void theHostKeepsItsOwnColons() {
        SavedDevices.SavedDevice device = SavedDevices.parseAddress("[fe80::1]:5555", "");

        assertEquals("[fe80::1]", device.host());
        assertEquals(5555, device.port());
    }

    @Test
    void renderingIsReadBackUnchanged() {
        List<SavedDevices.SavedDevice> devices = List.of(
                new SavedDevices.SavedDevice("192.168.1.5", 5555, "Pixel 7"),
                new SavedDevices.SavedDevice("10.0.0.42", 5555));

        assertEquals(devices, SavedDevices.parse(SavedDevices.render(devices)));
    }

    /** An entry that could not be read back must not be written in the first place. */
    @Test
    void anInvalidDeviceIsNotRendered() {
        String text = SavedDevices.render(List.of(new SavedDevices.SavedDevice("", 5555, "nowhere"),
                new SavedDevices.SavedDevice("192.168.1.5", 0, "no port")));

        assertTrue(SavedDevices.parse(text).isEmpty(), text);
    }

    @Test
    void aSavedDeviceSurvivesTheFile(@TempDir Path dir) {
        System.setProperty(SavedDevices.FILE_PROPERTY, dir.resolve("devices.txt").toString());

        assertTrue(SavedDevices.add(new SavedDevices.SavedDevice("192.168.1.5", 5555, "Pixel 7")));

        assertEquals(1, SavedDevices.load().size());
        assertEquals("Pixel 7", SavedDevices.load().get(0).displayName());
    }

    /** Missing file, missing directory: an empty list, never an exception — discovery must not fail on it. */
    @Test
    void anAbsentFileIsAnEmptyList(@TempDir Path dir) {
        System.setProperty(SavedDevices.FILE_PROPERTY, dir.resolve("nope/devices.txt").toString());

        assertTrue(SavedDevices.load().isEmpty());
    }

    /**
     * Re-adding an address renames it. The address is the identity every cache and de-dup keys on, so a second
     * row for one phone would be a row that can never be told apart from the first.
     */
    @Test
    void reAddingAnAddressRenamesRatherThanDuplicates(@TempDir Path dir) {
        System.setProperty(SavedDevices.FILE_PROPERTY, dir.resolve("devices.txt").toString());

        SavedDevices.add(new SavedDevices.SavedDevice("192.168.1.5", 5555, "Old name"));
        SavedDevices.add(new SavedDevices.SavedDevice("192.168.1.5", 5555, "Pixel 7"));

        assertEquals(1, SavedDevices.load().size());
        assertEquals("Pixel 7", SavedDevices.load().get(0).name());
    }

    @Test
    void removingForgetsOnlyThatAddress(@TempDir Path dir) {
        System.setProperty(SavedDevices.FILE_PROPERTY, dir.resolve("devices.txt").toString());
        SavedDevices.add(new SavedDevices.SavedDevice("192.168.1.5", 5555, "Pixel 7"));
        SavedDevices.add(new SavedDevices.SavedDevice("10.0.0.42", 5555, "Tablet"));

        assertTrue(SavedDevices.remove(new AdbEndpoint.Tcp("192.168.1.5", 5555)));

        assertEquals(List.of("10.0.0.42:5555"),
                SavedDevices.load().stream().map(d -> d.endpoint().label()).toList());
        assertFalse(SavedDevices.remove(new AdbEndpoint.Tcp("192.168.1.5", 5555)),
                "forgetting what is already forgotten changes nothing");
    }

    /** A saved phone must reach discovery, or the dialog that writes it is writing to nothing. */
    @Test
    void aSavedPhoneIsDiscoveredAsAnInstance(@TempDir Path dir) {
        System.setProperty(SavedDevices.FILE_PROPERTY, dir.resolve("devices.txt").toString());
        SavedDevices.add(new SavedDevices.SavedDevice("192.168.1.5", 5555, "Pixel 7"));

        List<EmulatorInstance> instances = DevicePlatform.saved();

        assertEquals(1, instances.size());
        assertEquals("Pixel 7", instances.get(0).name());
        assertEquals(PlatformId.PHYSICAL, instances.get(0).platformId());
        assertEquals("device@192.168.1.5:5555", instances.get(0).identity(),
                "the address is the identity — naming the phone must not move it");
    }
}
