package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings files as this machine's emulators wrote them, 2026-11-11. */
class WindowsSettingsTest {

    /** LDPlayer 14's leidian0.config as it was installed: no adbDebug, no resolution. */
    private static final String LD_NEW = """
            {
                "propertySettings.phoneModel": "GFE4J",
                "statusSettings.isNewPlayer": true,
                "basicSettings.width": 1416,
                "basicSettings.height": 797
            }""";

    private static final String LD_ON = """
            {
                "basicSettings.adbDebug": 1,
                "basicSettings.width": 1600,
                "advancedSettings.resolution": {
                    "width": 1600,
                    "height": 900
                },
                "advancedSettings.resolutionDpi": 240
            }""";

    private static final String BLUESTACKS = """
            bst.enable_adb_access="0"
            bst.enable_adb_remote_access="0"
            bst.instance.Pie64.adb_port="5555"
            bst.instance.Pie64.dpi="240"
            bst.instance.Pie64.fb_height="900"
            bst.instance.Pie64.fb_width="1600"
            bst.instance.Pie64.ram="4096"
            """;

    private static final String MUMU = """
            {
              "vm": {
                "cpu": "4",
                "memory": "4",
                "nat": { "port_forward": { "adb": { "host_port": "16384" } } }
              }
            }""";

    private static final String MEMU = """
            <Hardware>
            <Memory RAMSize="6144"/>
            <GuestProperty name="resolution_height" value="900" timestamp="1" flags=""/>
            <GuestProperty name="resolution_width" value="1600" timestamp="1" flags=""/>
            <GuestProperty name="vbox_dpi" value="300" timestamp="1" flags=""/>
            </Hardware>""";

    private static final Path FILE = Path.of("settings");

    @Test
    void ldPlayerHasAdbOffUntilItsFileSaysOtherwise() {
        WindowsSettings fresh = WindowsSettings.ldPlayer(FILE, LD_NEW);
        assertEquals(Boolean.FALSE, fresh.adbOn());
        assertFalse(fresh.hasSize());
        WindowsSettings on = WindowsSettings.ldPlayer(FILE, LD_ON);
        assertEquals(Boolean.TRUE, on.adbOn());
        assertEquals(List.of(1600, 900, 240), List.of(on.width(), on.height(), on.dpi()));
    }

    @Test
    void blueStacksReadsTheEditionsSwitchAndTheInstancesScreen() {
        WindowsSettings pie = WindowsSettings.blueStacks(FILE, BLUESTACKS, "Pie64");
        assertEquals(Boolean.FALSE, pie.adbOn());
        assertEquals(List.of(4096, 1600, 900, 240), List.of(pie.ramMb(), pie.width(), pie.height(), pie.dpi()));
        assertEquals(-1, WindowsSettings.blueStacks(FILE, BLUESTACKS, "Rvc64").width(), "another instance's");
    }

    @Test
    void muMuAndMemuHaveNoAdbSwitch() {
        WindowsSettings mumu = WindowsSettings.muMu(FILE, MUMU);
        assertNull(mumu.adbOn());
        assertEquals(4096, mumu.ramMb(), "vm.memory is in GB");
        WindowsSettings memu = WindowsSettings.memu(FILE, MEMU);
        assertNull(memu.adbOn());
        assertEquals(List.of(6144, 1600, 900, 300), List.of(memu.ramMb(), memu.width(), memu.height(), memu.dpi()));
    }

    @Test
    void turningAdbOnChangesOneSettingAndKeepsTheRest() {
        String ld = WindowsSettings.ldPlayerWithAdb(LD_NEW);
        assertEquals(Boolean.TRUE, WindowsSettings.ldPlayer(FILE, ld).adbOn());
        assertTrue(ld.contains("\"basicSettings.height\": 797"), ld);
        assertTrue(ld.trim().startsWith("{") && ld.trim().endsWith("}"), ld);
        String off = LD_ON.replace("\"basicSettings.adbDebug\": 1", "\"basicSettings.adbDebug\": 0");
        assertEquals(LD_ON, WindowsSettings.ldPlayerWithAdb(off));
        assertEquals(off, WindowsSettings.ldPlayerWithAdb(LD_ON, false), "Undo puts the switch back");
        assertEquals(LD_NEW, WindowsSettings.ldPlayerWithAdb(LD_NEW, false), "a missing key is off already");

        String bs = WindowsSettings.blueStacksWith(BLUESTACKS, "bst.enable_adb_access", "1");
        assertEquals(BLUESTACKS.replace("bst.enable_adb_access=\"0\"", "bst.enable_adb_access=\"1\""), bs);
        assertTrue(WindowsSettings.blueStacksWith("a=\"1\"\r\n", "b", "2").endsWith("b=\"2\"\r\n"), "a new key");
    }

    @Test
    void theFileIsFoundFromTheLaunchCommand() {
        Path ld = Path.of("C:", "LDPlayer", "LDPlayer14", "ldconsole.exe");
        EmulatorInstance ldPlayer = new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1", 5555,
                List.of(ld.toString(), "launch", "--index", "0"), List.of());
        assertEquals(ld.resolveSibling("vms").resolve("config").resolve("leidian0.config"),
                WindowsSettings.fileOf(ldPlayer).orElseThrow());

        Path memuc = Path.of("C:", "Program Files", "Microvirt", "MEmu", "memuc.exe");
        EmulatorInstance memu = new EmulatorInstance(PlatformId.MEMU, "MEmu", "127.0.0.1", 21503,
                List.of(memuc.toString(), "start", "-n", "MEmu"), List.of());
        assertEquals(memuc.resolveSibling("MemuHyperv VMs").resolve("MEmu").resolve("MEmu.memu"),
                WindowsSettings.fileOf(memu).orElseThrow());

        assertTrue(WindowsSettings.fileOf(new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1", 5555))
                .isEmpty(), "no launch command, no file");
        assertTrue(WindowsSettings.fileOf(new EmulatorInstance(PlatformId.AVD, "Pixel_8", "127.0.0.1", 5554,
                List.of("C:\\Android\\emulator\\emulator.exe", "-avd", "Pixel_8"), List.of())).isEmpty(),
                "a product that keeps no settings file BotMaker knows");
    }
}
