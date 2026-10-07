package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How current versions of each Windows product are found: their uninstall entries, the console tools' instance
 * lists, MuMu's forwarded port and BlueStacks' requested one. The strings are what the products print and write
 * on a real machine (LDPlayer 14, MEmu 9.5, MuMu Global with Android 15, MSI App Player 5.22).
 */
class WindowsDiscoveryTest {

    @Test
    void anUninstallEntryNamesItsProgramQuotedOrBareWithArgumentsAndIconIndexDropped() {
        assertEquals("C:\\Program Files\\Netease\\MuMuPlayer\\nx_main\\MuMuNxMain.ico",
                InstallLocator.programPath("\"C:\\Program Files\\Netease\\MuMuPlayer\\nx_main\\MuMuNxMain.ico\""));
        assertEquals("C:\\Program Files\\Microvirt\\MEmu\\uninstall\\uninstall.exe",
                InstallLocator.programPath("\"C:\\Program Files\\Microvirt\\MEmu\\uninstall\\uninstall.exe\" -u"));
        assertEquals("C:\\Program Files\\BlueStacks_msi5\\BlueStacksUninstaller.exe",
                InstallLocator.programPath("C:\\Program Files\\BlueStacks_msi5\\BlueStacksUninstaller.exe -tmp"));
        assertEquals("C:\\Program Files\\Microvirt\\MEmu\\MEmu.ico",
                InstallLocator.programPath("\"C:\\Program Files\\Microvirt\\MEmu\\MEmu.ico\",0"));
        assertEquals("C:\\LDPlayer\\LDPlayer14\\dnplayer.exe",
                InstallLocator.programPath("C:\\LDPlayer\\LDPlayer14\\dnplayer.exe,0"));
        assertNull(InstallLocator.programPath(" "));
    }

    @Test
    void anUninstallEntryWithoutInstallLocationIsFoundByItsIconThenItsUninstaller() {
        var ldPlayer14 = new InstallLocator.UninstallEntry("LDPlayer14", "LDPlayer 14", "",
                "C:\\LDPlayer\\LDPlayer14\\dnplayer.exe", "C:\\LDPlayer\\LDPlayer14\\dnuninst.exe");
        assertEquals(Path.of("C:\\LDPlayer\\LDPlayer14"), ldPlayer14.folder());

        var memu = new InstallLocator.UninstallEntry("MEmu", "MEmu", "C:\\Program Files\\Microvirt",
                "\"C:\\Program Files\\Microvirt\\MEmu\\MEmu.ico\",0", null);
        assertEquals(Path.of("C:\\Program Files\\Microvirt"), memu.folder(), "InstallLocation wins when written");

        var onlyUninstaller = new InstallLocator.UninstallEntry("X", null, null, null, "\"D:\\X\\u.exe\" /S");
        assertEquals(Path.of("D:\\X"), onlyUninstaller.folder());
        assertNull(new InstallLocator.UninstallEntry("Y", null, null, null, null).folder());
    }

    @Test
    void theConsoleToolsInstanceListsGiveTheNamesTheUserSees() {
        assertEquals(Map.of(0, "LDPlayer", 1, "Farm"),
                InstallLocator.titlesByIndex("0,LDPlayer,0,0,0,-1,-1,1600,900,240\r\n1,Farm,132,98,1,4410,4422,960,540,240\r\n"));
        assertEquals(Map.of(0, "MEmu"), InstallLocator.titlesByIndex("0,MEmu,0,0,0\n\n"));
        assertEquals(Map.of(), InstallLocator.titlesByIndex("ERROR: not found"));
        assertEquals(Map.of(), InstallLocator.titlesByIndex(null));
    }

    @Test
    void memucNumbersItsVmFoldersFromZero() {
        assertEquals(0, MemuPlatform.vmIndex("MEmu"));
        assertEquals(3, MemuPlatform.vmIndex("MEmu_3"));
        assertNull(MemuPlatform.vmIndex("Other"));
    }

    @Test
    void mumuTakesItsPortFromTheForwardedOneInItsConfig() {
        String vmConfig = """
                {
                  "vm": {
                    "nat": {
                      "port_forward": {
                        "adb": {
                          "host_port": "16416"
                        },
                        "api": {
                          "host_port": "17410"
                        }
                      }
                    }
                  }
                }""";
        EmulatorInstance instance = MuMuPlatform.parseInstance("MuMuPlayerGlobal-15.0-0", vmConfig).orElseThrow();
        assertEquals("127.0.0.1:16416", instance.endpoint(), "the forwarded port, not 16384 + 32 * 0");
        assertEquals("MuMu-0", instance.name());
    }

    @Test
    void mumuManagerReportsEachInstancesNameAsJsonWhetherOneOrMany() {
        String many = """
                {
                  "0": {"index": "0", "is_android_started": false, "name": "Android Device"},
                  "2": {"index": "2", "name": "Farm"}
                }""";
        assertEquals(Map.of(0, "Android Device", 2, "Farm"), MuMuPlatform.parseInfo(many));
        assertEquals(Map.of(1, "Solo"), MuMuPlatform.parseInfo("{\"index\": \"1\", \"name\": \"Solo\"}"));
        assertEquals(Map.of(), MuMuPlatform.parseInfo("not json"));
        assertEquals(Map.of(), MuMuPlatform.parseInfo(null));
    }

    @Test
    void mumusConsoleMovedUnderNxMain(@TempDir Path install) throws Exception {
        assertNull(MuMuPlatform.consoleOf(install));
        Path shell = Files.createDirectories(install.resolve("shell")).resolve("MuMuManager.exe");
        Files.writeString(shell, "");
        assertEquals(shell, MuMuPlatform.consoleOf(install));
        Path nxMain = Files.createDirectories(install.resolve("nx_main")).resolve("MuMuManager.exe");
        Files.writeString(nxMain, "");
        assertEquals(nxMain, MuMuPlatform.consoleOf(install));
    }

    @Test
    void ldPlayersConsoleIsLdconsoleElseTheOlderDnconsole(@TempDir Path install) throws Exception {
        assertNull(LdPlayerPlatform.consoleOf(install));
        Files.writeString(install.resolve("dnconsole.exe"), "");
        assertEquals(install.resolve("dnconsole.exe"), LdPlayerPlatform.consoleOf(install));
        Files.writeString(install.resolve("ldconsole.exe"), "");
        assertEquals(install.resolve("ldconsole.exe"), LdPlayerPlatform.consoleOf(install));
    }

    @Test
    void aBlueStacksInstanceNeverStartedHasOnlyItsRequestedPortAndAStartedOneItsTakenOne() {
        // MSI App Player beside BlueStacks: both ask for 5555, MSI's engine got 5556.
        String msi = String.join("\n",
                "bst.instance.Pie64.adb_port=\"5555\"",
                "bst.instance.Pie64.display_name=\"MSI App Player\"",
                "bst.instance.Pie64.status.adb_port=\"5556\"",
                "bst.instance.Pie64_1.adb_port=\"5565\"");
        List<EmulatorInstance> instances = BlueStacksPlatform.parseConf(msi);
        assertEquals(2, instances.size());
        assertEquals("MSI App Player", instances.get(0).name());
        assertEquals("127.0.0.1:5556", instances.get(0).endpoint());
        assertEquals("Pie64_1", instances.get(1).name());
        assertEquals("127.0.0.1:5565", instances.get(1).endpoint());
    }

    @Test
    void gameLoopsEngineIsUnderUiAndWithoutItThereIsNoInstance(@TempDir Path install) throws Exception {
        assertNull(GameloopPlatform.engine(List.of(install)));
        Path ui = Files.createDirectories(install.resolve("ui"));
        Files.writeString(ui.resolve("AndroidEmulatorEn.exe"), "");
        assertEquals(ui.resolve("AndroidEmulatorEn.exe"), GameloopPlatform.engine(List.of(install)));
    }

    @Test
    void twoProductsOnOnePortBothStayAndOnlyAPhoneSeenTwiceGoes() {
        EmulatorInstance blueStacks = new EmulatorInstance(PlatformId.BLUESTACKS, "BlueStacks", "127.0.0.1", 5555);
        EmulatorInstance ldPlayer = new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1", 5555);
        EmulatorInstance sameAsServer = new EmulatorInstance(PlatformId.PHYSICAL, "127.0.0.1:5555", "127.0.0.1", 5555);
        EmulatorInstance phone = new EmulatorInstance(PlatformId.PHYSICAL, "Pixel", "192.168.1.5", 5555);
        EmulatorInstance phoneAgain = new EmulatorInstance(PlatformId.PHYSICAL, "Pixel", "192.168.1.5", 5555);

        assertEquals(List.of(blueStacks, ldPlayer, phone),
                Platforms.dedupe(List.of(blueStacks, ldPlayer, sameAsServer, phone, phoneAgain)));
    }

    /** What discovery finds on this machine; asserts nothing about it, since every machine has its own. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "botmaker.live", matches = "true")
    void printsWhatThisMachineHas() {
        long start = System.nanoTime();
        Platforms.DiscoveryReport report = Platforms.discoverDetailed();
        System.out.printf("discovery took %d ms%n", (System.nanoTime() - start) / 1_000_000);
        report.statuses().forEach(s -> System.out.println("  " + s.statusLine()));
        report.instances().forEach(i -> System.out.println("  " + i.caption() + " @ " + i.endpoint()
                + (i.canLaunch() ? "  start: " + String.join(" ", i.launchCommand()) : "")));
    }

    @Test
    void anInstalledProductWithNoInstanceSaysWhyWhenItCan() {
        assertEquals("Gameloop: installed · only the launcher is installed",
                new Platforms.PlatformStatus(PlatformId.GAMELOOP, true, 0, null, "only the launcher is installed")
                        .statusLine());
        assertEquals("MEmu: installed · no instances configured",
                new Platforms.PlatformStatus(PlatformId.MEMU, true, 0, null).statusLine());
    }
}
