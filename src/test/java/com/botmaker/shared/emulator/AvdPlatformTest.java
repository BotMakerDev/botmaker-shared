package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Android Studio's emulators: every AVD listed by its id, where it runs read from its process or its serial. */
class AvdPlatformTest {

    private static final Path EMULATOR = Path.of("/sdk/emulator/emulator");
    private static final Path ADB = Path.of("/sdk/platform-tools/adb");

    @Test
    void listsEveryIniByName(@TempDir Path avdHome) throws Exception {
        Files.writeString(avdHome.resolve("Pixel_7.ini"), "path=" + avdHome.resolve("Pixel_7.avd") + "\n");
        Files.createDirectories(avdHome.resolve("Pixel_7.avd"));
        Files.writeString(avdHome.resolve("Pixel_7.avd/config.ini"), "avd.ini.displayname=Pixel 7 API 34\n");
        // An .ini whose folder is gone is still an AVD the user made: listed, the emulator says what is wrong.
        Files.writeString(avdHome.resolve("Broken.ini"), "path=/nowhere\n");
        Files.writeString(avdHome.resolve("notes.txt"), "");

        assertEquals(List.of("Broken", "Pixel_7"), AvdPlatform.avds(avdHome));
        assertEquals(List.of(), AvdPlatform.avds(avdHome.resolve("missing")));
    }

    @Test
    void readsPortFromArgs() {
        Map<String, Integer> running = AvdPlatform.runningByArgs(List.of(
                List.of("/sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64", "-avd", "Pixel_7", "-port", "5556"),
                List.of("/sdk/emulator/emulator", "-avd", "Pixel_7"),
                List.of("/sdk/emulator/emulator", "@Tablet", "-no-snapshot-save"),
                List.of("/sdk/emulator/emulator", "-avd", "Wear", "-ports", "5570,5571")));

        assertEquals(Map.of("Pixel_7", 5556, "Tablet", -1, "Wear", 5570), running);
    }

    @Test
    void plannedPortsSkipTakenAndUnbindable() {
        assertEquals(List.of(5558, 5560),
                AvdPlatform.plannedPorts(2, Set.of(5554), port -> port != 5557));
        assertEquals(List.of(), AvdPlatform.plannedPorts(1, Set.of(), port -> false), "nothing free, nothing planned");
    }

    /** The picker polls the instance it started: a stopped AVD keeps its address while nothing else changed. */
    @Test
    void plannedPortsAreStable() {
        List<String> avds = List.of("A", "B");
        List<EmulatorInstance> first = AvdPlatform.instances(avds, Map.of(), List.of(), Map.of(), p -> true,
                EMULATOR, ADB);
        List<EmulatorInstance> again = AvdPlatform.instances(avds, Map.of(), List.of(), Map.of(), p -> true,
                EMULATOR, ADB);

        assertEquals(first, again);
        assertEquals(List.of("emulator-5554", "emulator-5556"), first.stream().map(EmulatorInstance::endpoint).toList());
    }

    @Test
    void commandsNameThePort() {
        EmulatorInstance avd = AvdPlatform.instance("Pixel_7", 5556, EmulatorState.STOPPED, EMULATOR, ADB);

        assertEquals(List.of(EMULATOR.toString(), "-avd", "Pixel_7", "-port", "5556"), avd.launchCommand());
        assertEquals(List.of(ADB.toString(), "-s", "emulator-5556", "emu", "kill"), avd.stopCommand());
        assertEquals("Pixel_7", avd.reference(), "a bot names it by its id, whatever its port");
        assertEquals("Android Studio emulator: Pixel_7", avd.caption());
        assertTrue(AvdPlatform.instance("Pixel_7", 5556, EmulatorState.STOPPED, EMULATOR, null).stopCommand().isEmpty());
    }

    /** Where each runs: its process's port, else the serial that names it; a stopped one at a port nobody holds. */
    @Test
    void runningOnesWhereTheyRunStoppedOnesBesideThem() {
        List<AdbTools.ServerDevice> devices = List.of(
                new AdbTools.ServerDevice("emulator-5554", "device", "sdk_gphone64", false),
                new AdbTools.ServerDevice("emulator-5556", "offline", "", false));
        List<EmulatorInstance> found = AvdPlatform.instances(List.of("Booting", "Pixel_7", "Stopped"),
                Map.of("Booting", 5556), devices, Map.of("emulator-5554", "Pixel_7"), p -> true, EMULATOR, ADB);

        assertEquals(List.of("emulator-5556", "emulator-5554", "emulator-5558"),
                found.stream().map(EmulatorInstance::endpoint).toList());
        assertEquals(List.of(EmulatorState.STARTING, EmulatorState.RUNNING, EmulatorState.STOPPED),
                found.stream().map(EmulatorInstance::state).toList());
    }

    /** An LDPlayer up on 127.0.0.1:5555 holds the port an AVD would plan on 5554: the next one is planned. */
    @Test
    void aPortAnotherEmulatorHoldsIsSkipped() {
        List<EmulatorInstance> found = AvdPlatform.instances(List.of("Pixel_7"), Map.of(), List.of(), Map.of(),
                port -> port != 5555, EMULATOR, ADB);

        assertEquals("emulator-5556", found.getFirst().endpoint());
    }

    /** Android Studio passes no port, and a booting serial cannot say its name: the unnamed serial is that AVD's. */
    @Test
    void aProcessWithNoPortTakesTheSerialThatNamedNoAvd() {
        List<AdbTools.ServerDevice> devices = List.of(
                new AdbTools.ServerDevice("emulator-5554", "device", "ASUS_I003DD", false),
                new AdbTools.ServerDevice("emulator-5556", "offline", "", false));
        List<EmulatorInstance> found = AvdPlatform.instances(List.of("Pixel_7"), Map.of("Pixel_7", -1), devices,
                Map.of("emulator-5554", ""), p -> true, EMULATOR, ADB);

        assertEquals("emulator-5556", found.getFirst().endpoint(), "not the device that said it is no AVD");
        assertEquals(EmulatorState.STARTING, found.getFirst().state());
    }

    /** The server's connection number is what tells a new emulator on a serial from the one before it. */
    @Test
    void theTransportIdIsRead() {
        List<AdbTools.ServerDevice> devices = AdbTools.parseDevices("""
                List of devices attached
                emulator-5554 device product:sdk model:sdk_gphone64 device:emu64x transport_id:7
                """);

        assertEquals("7", devices.getFirst().transportId());
    }

    @Test
    void serialPorts() {
        assertEquals(5554, AvdPlatform.serialPort("emulator-5554"));
        assertEquals(-1, AvdPlatform.serialPort("192.168.1.5:5555"));
        assertEquals(-1, AvdPlatform.serialPort("emulator-x"));
    }
}
