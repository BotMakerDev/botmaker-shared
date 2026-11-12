package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.InstalledProduct;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.WaydroidResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Windows emulators' probes, their Fixes' pieces, and what stops a launch. */
class WindowsEmulatorChecksTest {

    private static final WaydroidResolution PORTRAIT = new WaydroidResolution(900, 1600);

    @Test
    void adbOffInAFileBotMakerWritesIsFixedElseSaidWhereToTurnItOn() {
        CheckResult ld = WindowsEmulatorChecks.adb(PlatformId.LDPLAYER, false, false);
        assertTrue(ld.failed() && ld.fixable(), ld.toString());
        CheckResult closed = WindowsEmulatorChecks.adb(PlatformId.LDPLAYER, null, true);
        assertTrue(closed.failed() && !closed.fixable());
        assertTrue(closed.remedy().contains("ADB debugging"), closed.remedy());
        assertFalse(WindowsEmulatorChecks.adb(PlatformId.MUMU, null, false).failed());
        assertEquals("ADB is on.", WindowsEmulatorChecks.adb(PlatformId.BLUESTACKS, true, false).reason());
        assertEquals(CheckState.INFO, WindowsEmulatorChecks.adb(PlatformId.LDPLAYER, null, false).state(),
                "an unread file is not ADB on");
        assertTrue(EmulatorChecks.stoppingLaunch(List.of(WindowsEmulatorChecks.adb(PlatformId.LDPLAYER, true, true)))
                .isEmpty(), "a port not answering yet may be adbd binding it");
    }

    @Test
    void aPortAnotherEmulatorHoldsIsSaid() {
        assertFalse(WindowsEmulatorChecks.portFree(null).failed());
        CheckResult clash = WindowsEmulatorChecks.portFree("port 5555 is in use by BlueStacks: Pie64");
        assertTrue(clash.failed());
        assertEquals("Port 5555 is in use by BlueStacks: Pie64.", clash.reason());
        // This PC, 2026-11-11, a French Windows: MuMu's device service listens on 5555.
        String netstat = """
                  Proto  Adresse locale         Adresse distante       État
                  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1234
                  TCP    127.0.0.1:5555         0.0.0.0:0              ÉCOUTE          31008
                  TCP    127.0.0.1:54901        127.0.0.1:5555         TIME_WAIT       0
                """;
        assertEquals(31008, WindowsEmulatorChecks.listener(netstat, 5555));
        assertEquals(-1, WindowsEmulatorChecks.listener(netstat, 5556));
    }

    @Test
    void memoryWindowsCannotCommitFailsBeforeAStart() {
        assertTrue(WindowsEmulatorChecks.memory(6144, 3000, false).failed());
        assertFalse(WindowsEmulatorChecks.memory(4096, 8000, false).failed());
        assertNull(WindowsEmulatorChecks.memory(6144, 3000, true), "it already runs with it");
        assertNull(WindowsEmulatorChecks.memory(-1, 3000, false), "the file names none");
        assertNull(WindowsEmulatorChecks.memory(6144, -1, false), "Windows did not say");
    }

    @Test
    void armAndPlayAreReadFromAndroid() {
        assertFalse(WindowsEmulatorChecks.arm("x86_64,arm64-v8a,x86,armeabi-v7a,armeabi").failed());
        assertTrue(WindowsEmulatorChecks.arm("x86_64,x86").failed());
        assertEquals(CheckState.INFO, WindowsEmulatorChecks.arm(null).state());
        assertEquals(CheckState.OK, WindowsEmulatorChecks.googlePlay(true).state());
        assertEquals(CheckState.INFO, WindowsEmulatorChecks.googlePlay(false).state(), "files install anyway");
        assertEquals(CheckState.INFO, WindowsEmulatorChecks.googlePlay(null).state());
    }

    @Test
    void aLandscapeScreenForAPortraitBotIsFixedWhereBotMakerCanSetIt() {
        CheckResult ld = WindowsEmulatorChecks.resolution(PlatformId.LDPLAYER, 1600, 900, PORTRAIT);
        assertTrue(ld.failed() && ld.fixable(), ld.toString());
        CheckResult mumu = WindowsEmulatorChecks.resolution(PlatformId.MUMU, 1600, 900, PORTRAIT);
        assertTrue(mumu.failed() && !mumu.fixable());
        assertFalse(WindowsEmulatorChecks.resolution(PlatformId.MEMU, 900, 1600, PORTRAIT).failed());
        assertNull(WindowsEmulatorChecks.resolution(PlatformId.MEMU, 1600, 900, null), "no bot size");
        assertArrayEquals(new int[]{1080, 1920}, WindowsEmulatorChecks.wmSize("Physical size: 900x1600\n"
                + "Override size: 1080x1920"));
        assertArrayEquals(new int[]{900, 1600}, WindowsEmulatorChecks.wmSize("Physical size: 900x1600"));
        assertNull(WindowsEmulatorChecks.wmSize("error"));
    }

    @Test
    void anOlderVersionIsInstalledOverWhereAnInstallerIsPinnedAndANewerOneOnlySaid() {
        CheckResult same = WindowsEmulatorChecks.version(new InstalledProduct("LDPlayer14", "LDPlayer 14", "14.0.29.0"));
        assertEquals(CheckState.OK, same.state(), same.toString());
        CheckResult older = WindowsEmulatorChecks.version(new InstalledProduct("LDPlayer14", "LDPlayer 14", "14.0.9.0"));
        assertTrue(older.failed() && older.fixable(), older.toString());
        assertTrue(older.remedy().contains("929 MB"), older.remedy());
        CheckResult newer = WindowsEmulatorChecks.version(new InstalledProduct("LDPlayer14", "LDPlayer 14", "14.0.34.1"));
        assertEquals(CheckState.INFO, newer.state());
        CheckResult memu = WindowsEmulatorChecks.version(new InstalledProduct("MEmu", "MEmu", "9.0.1"));
        assertTrue(memu.failed() && !memu.fixable(), "no pinned installer: by hand");
        assertEquals("https://www.memuplay.com/", memu.link());
        assertEquals(CheckState.INFO, WindowsEmulatorChecks.version(new InstalledProduct("LDPlayer9", "LDPlayer 9",
                "9.1.0")).state(), "an edition never tested");
        assertEquals(CheckState.INFO, WindowsEmulatorChecks.version(null).state());
        assertTrue(EmulatorChecks.stoppingLaunch(List.of(older)).isEmpty(), "a version never stops a launch");
    }

    @Test
    void versionsCompareByTheirNumbers() {
        assertTrue(TestedVersions.compare("7.0.19.05", "7.0.9") > 0);
        assertEquals(0, TestedVersions.compare("14.0.29", "14.0.29.0"));
        assertTrue(TestedVersions.compare("5.22.75.6322", "5.22.265.1012") < 0);
        assertEquals(14, TestedVersions.major("14.0.29.0"));
        assertEquals(-1, TestedVersions.major("beta"));
        for (TestedVersions.Tested tested : TestedVersions.ALL) {
            if (tested.installer() == null) continue;
            assertTrue(tested.installer().remote().wellFormed() && tested.installer().remote().secure(),
                    tested.key());
        }
        assertEquals("LDPlayer_14.0.29.0.exe", TestedVersions.LDPLAYER_14.fileName());
    }

    @Test
    void theHypervisorFailsOnlyABuildThatCantRunBesideIt() {
        InstalledProduct ld14 = new InstalledProduct("LDPlayer14", "LDPlayer 14", "14.0.29.0");
        assertNull(WindowsEmulatorChecks.hypervisor(PlatformId.LDPLAYER, ld14, null), "Windows did not say");
        assertEquals(CheckState.OK, WindowsEmulatorChecks.hypervisor(PlatformId.LDPLAYER, ld14, false).state());
        assertEquals(CheckState.OK, WindowsEmulatorChecks.hypervisor(PlatformId.LDPLAYER, ld14, true).state());
        CheckResult ld4 = WindowsEmulatorChecks.hypervisor(PlatformId.LDPLAYER,
                new InstalledProduct("LDPlayer4", "LDPlayer 4", "4.0.83"), true);
        assertTrue(ld4.failed() && !ld4.fixable(), ld4.toString());
        assertEquals(CheckState.INFO, WindowsEmulatorChecks.hypervisor(PlatformId.MEMU,
                new InstalledProduct("MEmu", "MEmu", "7.6.6"), true).state(), "not known to fail: only said");
        assertTrue(EmulatorChecks.stoppingLaunch(List.of(ld4)).isEmpty());
    }

    @Test
    void anInstallerRunsElevatedWithItsArgumentsEncoded() {
        List<String> command = ElevatedCopy.command(Path.of("C:\\Cache\\LDPlayer_14.0.29.0.exe"), "AB12",
                List.of("-silence"));
        String outer = command.getLast();
        assertTrue(outer.contains("-Verb RunAs") && outer.contains("exit $p.ExitCode"), outer);
        String encoded = outer.substring(outer.indexOf("'-EncodedCommand','") + 19, outer.lastIndexOf("'; exit"));
        String inner = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);
        assertEquals("if ((Get-FileHash -LiteralPath 'C:\\Cache\\LDPlayer_14.0.29.0.exe' -Algorithm SHA256).Hash "
                + "-ne 'AB12') { exit 1000001 }; $q = Start-Process -FilePath 'C:\\Cache\\LDPlayer_14.0.29.0.exe' "
                + "-ArgumentList '-silence' -Wait -PassThru; exit $q.ExitCode", inner);
    }

    @Test
    void anInstanceDiscoveryDidNotFindStopsALaunch() {
        assertTrue(WindowsEmulatorChecks.notFound(null).isEmpty(), "a spec naming no instance has no row");
        List<CheckResult> none = WindowsEmulatorChecks.notFound("Pie64", List.of());
        assertTrue(none.getFirst().fixable(), "LDPlayer's installer is pinned");
        assertTrue(none.getFirst().remedy().contains("LDPlayer 14 14.0.29.0"), none.getFirst().remedy());
        assertEquals(EmulatorCheck.EMULATOR_INSTALLED, EmulatorChecks.stoppingLaunch(none).orElseThrow().check());
        List<CheckResult> noInstance = WindowsEmulatorChecks.notFound("Pie64", List.of("LDPlayer", "MuMu Player"));
        CheckResult stopping = EmulatorChecks.stoppingLaunch(noInstance).orElseThrow();
        assertEquals(EmulatorCheck.INSTANCE_EXISTS, stopping.check());
        assertTrue(stopping.remedy().contains("LDPlayer or MuMu Player"), stopping.remedy());
        assertEquals(EmulatorCheck.ADB_ENABLED, EmulatorChecks.stoppingLaunch(List.of(
                WindowsEmulatorChecks.adb(PlatformId.BLUESTACKS, false, false))).orElseThrow().check());
        assertTrue(EmulatorChecks.stoppingLaunch(List.of(WindowsEmulatorChecks.memory(6144, 1, false))).isEmpty(),
                "a launch may still try");
    }

    @Test
    void aBackupIsKeptPerInstanceAndCheckAndUndoneOnce(@TempDir Path dir) throws Exception {
        Path settings = Files.writeString(dir.resolve("leidian0.config"), "{\"a\": 1}");
        Path root = dir.resolve("backups");
        EmulatorInstance ld = new EmulatorInstance(PlatformId.LDPLAYER, "My LD/Player", "127.0.0.1", 5555);
        assertEquals("ldplayer-My_LD_Player", WindowsBackups.folderOf(ld));
        WindowsBackups.save(root, ld, EmulatorCheck.ADB_ENABLED, settings, LocalDateTime.of(2026, 11, 11, 9, 0));
        WindowsBackups.Backup newer = WindowsBackups.save(root, ld, EmulatorCheck.ADB_ENABLED, settings,
                LocalDateTime.of(2026, 11, 11, 10, 0));
        assertEquals(Optional.empty(), WindowsBackups.newest(root, ld, EmulatorCheck.RESOLUTION));

        WindowsBackups.Backup found = WindowsBackups.newest(root, ld, EmulatorCheck.ADB_ENABLED).orElseThrow();
        assertEquals(newer.folder(), found.folder());
        assertEquals(settings.toAbsolutePath(), found.origin());
        assertEquals("{\"a\": 1}", Files.readString(found.copy()));
        WindowsBackups.undone(found);
        assertTrue(WindowsBackups.newest(root, ld, EmulatorCheck.ADB_ENABLED).orElseThrow().folder().getFileName()
                .toString().startsWith("20261111-090000"), "the one before it is next");
    }

    @Test
    void theElevatedCopyPassesItsPathsEncoded() {
        List<String> command = ElevatedCopy.command(Path.of("C:\\Temp\\it's.txt"),
                Path.of("C:\\ProgramData\\BlueStacks_nxt\\bluestacks.conf"));
        assertEquals("powershell.exe", command.getFirst());
        String outer = command.getLast();
        assertTrue(outer.contains("-Verb RunAs") && outer.contains("exit $p.ExitCode"), outer);
        assertFalse(outer.contains("bluestacks.conf"), "the paths travel only inside the encoded command");
        String encoded = outer.substring(outer.indexOf("'-EncodedCommand','") + 19, outer.lastIndexOf("'; exit"));
        String inner = new String(java.util.Base64.getDecoder().decode(encoded),
                java.nio.charset.StandardCharsets.UTF_16LE);
        assertEquals("Copy-Item -LiteralPath 'C:\\Temp\\it''s.txt' -Destination "
                + "'C:\\ProgramData\\BlueStacks_nxt\\bluestacks.conf' -Force -ErrorAction Stop", inner);
    }

    @Test
    void ldPlayersSizeGoesThroughItsConsole() {
        assertEquals(List.of("C:\\LD\\ldconsole.exe", "modify", "--index", "2", "--resolution", "900,1600,240"),
                WindowsFixes.ldPlayerSizeCommand(List.of("C:\\LD\\ldconsole.exe", "launch", "--index", "2"),
                        PORTRAIT, 240));
        assertNull(WindowsFixes.ldPlayerSizeCommand(List.of(), PORTRAIT, 240));
    }

    @Test
    void onlyAFixableFailureHasAFix() {
        EmulatorInstance ld = new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1", 5555);
        assertTrue(WindowsFixes.of(WindowsEmulatorChecks.adb(PlatformId.LDPLAYER, null, true), ld, null)
                .isEmpty());
        EmulatorInstance bs = new EmulatorInstance(PlatformId.BLUESTACKS, "Pie64", "127.0.0.1", 5555);
        Fix fix = WindowsFixes.of(WindowsEmulatorChecks.adb(PlatformId.BLUESTACKS, false, false), bs, null)
                .orElseThrow();
        assertEquals(List.of("Stopping Pie64", "Keeping a copy of its settings", "Turning on ADB",
                "Starting Pie64 again"), fix.words());
        assertFalse(fix.needsRoot(), "a UAC prompt, not a root password");
        assertTrue(WindowsFixes.of(WindowsEmulatorChecks.resolution(PlatformId.BLUESTACKS, 1600, 900, PORTRAIT), bs,
                null).isEmpty(), "no size to set it to");
    }
}
