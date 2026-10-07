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
    void theConsoleToolsInstanceListsGiveTheNamesTheUserSeesAndWhetherEachIsUp() {
        assertEquals(Map.of(
                        0, new InstallLocator.ConsoleRow("LDPlayer", EmulatorState.STOPPED),
                        1, new InstallLocator.ConsoleRow("Farm", EmulatorState.RUNNING),
                        2, new InstallLocator.ConsoleRow("Booting", EmulatorState.STARTING)),
                LdPlayerPlatform.parseList2("0,LDPlayer,0,0,0,-1,-1,1600,900,240\r\n"
                        + "1,Farm,132,98,1,4410,4422,960,540,240\r\n"
                        + "2,Booting,140,0,0,5120,5133,960,540,240\r\n"));
        assertEquals(Map.of(0, new InstallLocator.ConsoleRow("MEmu", EmulatorState.STOPPED),
                        1, new InstallLocator.ConsoleRow("MEmu_1", EmulatorState.STARTING)),
                MemuPlatform.parseListVms("0,MEmu,0,0,0\n1,MEmu_1,3344,1,7788\n\n"));
        assertEquals(Map.of(0, new InstallLocator.ConsoleRow("Short", EmulatorState.UNKNOWN)),
                MemuPlatform.parseListVms("0,Short"), "a line too short to say is not a stopped instance");
        assertEquals(Map.of(), LdPlayerPlatform.parseList2("ERROR: not found"));
        assertEquals(Map.of(), MemuPlatform.parseListVms(null));
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
    void mumuManagerReportsEachInstancesNameAndStateAsJsonWhetherOneOrMany() {
        String many = """
                {
                  "0": {"index": "0", "is_android_started": false, "is_process_started": false, "name": "Android Device"},
                  "1": {"index": "1", "is_android_started": false, "is_process_started": true, "name": "Booting"},
                  "2": {"index": "2", "is_android_started": true, "is_process_started": true, "name": "Farm"}
                }""";
        assertEquals(Map.of(
                        0, new InstallLocator.ConsoleRow("Android Device", EmulatorState.STOPPED),
                        1, new InstallLocator.ConsoleRow("Booting", EmulatorState.STARTING),
                        2, new InstallLocator.ConsoleRow("Farm", EmulatorState.RUNNING)),
                MuMuPlatform.parseInfo(many));
        assertEquals(Map.of(1, new InstallLocator.ConsoleRow("Solo", EmulatorState.UNKNOWN)),
                MuMuPlatform.parseInfo("{\"index\": \"1\", \"name\": \"Solo\"}"));
        assertEquals(Map.of(), MuMuPlatform.parseInfo("not json"));
        assertEquals(Map.of(), MuMuPlatform.parseInfo(null));
    }

    @Test
    void aBlueStacksEditionHasStartedOnlyWhenItsOneInstanceIsTheOnePlayerRunningAndOtherwiseThePortSays() {
        assertEquals(EmulatorState.STOPPED, BlueStacksPlatform.state(1, 0, false),
                "no player anywhere: MuMu answering on 5555 doesn't make BlueStacks run");
        assertEquals(EmulatorState.UNKNOWN, BlueStacksPlatform.state(1, 0, true), "a player under another path");
        assertEquals(EmulatorState.UNKNOWN, BlueStacksPlatform.state(1, 0, null), "a process table we can't read");
        assertEquals(EmulatorState.STARTING, BlueStacksPlatform.state(1, 1, true));
        assertEquals(EmulatorState.UNKNOWN, BlueStacksPlatform.state(2, 1, true), "which of the two is up, its port says");
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
        report.instances().forEach(i -> {
            EmulatorLiveness liveness = EmulatorLiveness.check(i);
            String problem = liveness.problem(i);
            System.out.println("  " + i.caption() + " @ " + i.endpoint() + " · " + liveness.label()
                    + (problem == null ? "" : " · " + problem)
                    + (i.canLaunch() ? "  start: " + String.join(" ", i.launchCommand()) : ""));
        });
    }

    /** The names read out of real APKs on whichever instance runs: system apps too, so there is always some. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "botmaker.live", matches = "true")
    void printsTheAppNamesOfARunningInstance() throws Exception {
        for (EmulatorInstance instance : Platforms.discoverAll()) {
            if (!EmulatorLiveness.running(instance)) continue;
            System.out.println("  " + instance.caption());
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                List<String> packages = AdbDevice.parsePackageList(device.shell("pm list packages"));
                for (String pkg : packages.stream().sorted().limit(40).toList()) {
                    long start = System.nanoTime();
                    String label = device.appLabel(pkg);
                    System.out.printf("    %-45s %-30s %d ms%n", pkg, label, (System.nanoTime() - start) / 1_000_000);
                }
            }
        }
    }

    /** What a running adb server has heard announced on this network, and which phones it isn't connected to. */
    @Test
    @EnabledIfSystemProperty(named = "botmaker.live", matches = "true")
    void printsThePhonesTheAdbServerHearsOnTheNetwork() {
        System.out.println("adb server running: " + AdbTools.serverRunning());
        AdbTools.mdnsServices().forEach(s -> System.out.println("  " + s.kind() + " " + s.displayName() + " "
                + s.address()));
        System.out.println("  not connected: " + AdbTools.unconnected(AdbTools.mdnsServices(), AdbTools.devices()));
    }

    /** How each installed product here adds an instance; runs none of them. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "botmaker.live", matches = "true")
    void printsHowEachProductMakesANewInstance() {
        Platforms.newInstances().forEach(way -> System.out.println("  " + way.label() + ": "
                + String.join(" ", way.command())));
    }

    /**
     * Installs {@code -Dbotmaker.apk=<file>} on the first running instance, then opens
     * {@code -Dbotmaker.store=<package>}'s Google Play page there and stops waiting after a minute.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @EnabledIfSystemProperty(named = "botmaker.apk", matches = ".+")
    void installsAFileAndOpensGooglePlayOnARunningInstance() throws Exception {
        EmulatorInstance instance = Platforms.discoverAll().stream().filter(EmulatorLiveness::running).findFirst()
                .orElseThrow();
        long start = System.nanoTime();
        EmulatorInstall.Result result = EmulatorInstall.fromFile(instance,
                java.nio.file.Path.of(System.getProperty("botmaker.apk")), line -> System.out.println("  … " + line));
        System.out.printf("  %s in %d ms%n", result, (System.nanoTime() - start) / 1_000_000);
        System.out.println("  cached: " + EmulatorAppCache.shared().packages(instance));
        String store = System.getProperty("botmaker.store");
        if (store == null) return;
        Thread wait = new Thread(() -> System.out.println("  " + EmulatorInstall.fromStore(instance, store, null,
                line -> System.out.println("  … " + line))));
        wait.start();
        wait.join(60_000);
        wait.interrupt();
        wait.join();
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
