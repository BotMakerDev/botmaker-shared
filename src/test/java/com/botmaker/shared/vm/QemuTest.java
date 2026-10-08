package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QEMU's command line for a game VM, and VMware's commands and output. */
class QemuTest {

    private static final VmSpec SPEC = new VmSpec("game", Path.of("vm"), new VmSize(4, 8192, 80), Path.of("win.iso"),
            List.of(Path.of("answer.iso"), Path.of("virtio,win.iso")), 5902);

    @Test
    void theCommandLineRunsHeadlessOnTheHypervisorPlatformWithLoopbackScreenAndControl() {
        Qemu qemu = new Qemu(Path.of("qemu"));
        List<String> c = qemu.command(SPEC, Path.of("disk.qcow2"), Path.of("efivars.fd"),
                new Qemu.Ports(4444, 4445, 4446));
        String line = String.join(" ", c);

        assertEquals(qemu.system().toString(), c.getFirst());
        assertEquals("whpx", c.get(c.indexOf("-accel") + 1), "the Hypervisor Platform first, with its own interrupt controller");
        assertEquals("tcg", c.get(c.lastIndexOf("-accel") + 1), "then QEMU's own emulator");
        assertTrue(c.contains("-no-reboot"), "a guest restart ends QEMU, which is started again");
        assertEquals("4", c.get(c.indexOf("-smp") + 1));
        assertEquals("8192M", c.get(c.indexOf("-m") + 1));
        assertTrue(line.contains("if=pflash,format=raw,readonly=on,file=" + Path.of("qemu", "share", "edk2-x86_64-code.fd")));
        assertTrue(line.contains("-device nvme,drive=disk0"));
        assertTrue(line.contains("-device usb-tablet"), "a VNC pointer lands where it is sent");
        assertTrue(line.contains("file=win.iso -device ide-cd,id=cdrom0,drive=cd0,bus=ide.0"));
        assertTrue(line.contains("file=answer.iso -device ide-cd,id=cdrom1,drive=cd1,bus=ide.1"));
        assertEquals(3, Qemu.cdDrives(SPEC), "the drives a ready VM's discs are ejected from");
        assertTrue(line.contains("file=virtio,,win.iso"), "a comma in a path is doubled");
        assertEquals("127.0.0.1:2,password=on", c.get(c.indexOf("-vnc") + 1));
        assertEquals("tcp:127.0.0.1:4444,server=on,wait=off", c.get(c.indexOf("-qmp") + 1));
        assertEquals("tcp:127.0.0.1:4446,server=on,wait=off", c.get(c.lastIndexOf("-qmp") + 1),
                "a second QMP, for whoever listens to why the VM stops");
        assertTrue(line.contains("port=4445,server=on,wait=off"));
        assertTrue(line.contains("name=org.qemu.guest_agent.0"));
        assertEquals("none", c.get(c.indexOf("-display") + 1));
    }

    @Test
    void installingIsOneSilentWingetCommand() {
        assertEquals(List.of("winget", "install", "-e", "--id", "SoftwareFreedomConservancy.QEMU", "--silent",
                "--accept-package-agreements", "--accept-source-agreements"), Qemu.installCommand());
        assertThrows(IllegalArgumentException.class, () -> new Qemu.Ports(4444, 4445, 4444));
        VmSpec far = new VmSpec("game", Path.of("vm"), new VmSize(1, 4096, 80), Path.of("w.iso"), List.of(), 6000);
        assertThrows(IllegalArgumentException.class,
                () -> new Qemu(Path.of("q")).command(far, Path.of("d"), Path.of("v"), new Qemu.Ports(4444, 4445, 4446)));
    }

    @Test
    void vmwaresCommandsAndItsListOfRunningVms() {
        VmwareWorkstation ws = new VmwareWorkstation(Path.of("ws"));
        Path vmx = Path.of("C:\\VMs\\game\\game.vmx");
        assertEquals(List.of(ws.vmrun().toString(), "-T", "ws", "start", vmx.toString(), "nogui"), ws.startCommand(vmx));
        assertEquals(List.of(ws.vmrun().toString(), "-T", "ws", "-gu", "botmaker", "-gp", "pw", "fileExistsInGuest",
                vmx.toString(), "C:\\BotMaker\\ready"), ws.fileExistsCommand(vmx, "botmaker", "pw", "C:\\BotMaker\\ready"));
        // PowerShell started straight by vmrun exits 1 at once: cmd starts it, its output into a file.
        assertEquals(List.of(ws.vmrun().toString(), "-T", "ws", "-gu", "botmaker", "-gp", "pw", "runProgramInGuest",
                vmx.toString(), "C:\\Windows\\System32\\cmd.exe", "/c C:\\Windows\\System32\\WindowsPowerShell\\v1.0"
                        + "\\powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\\BotMaker\\s.ps1"
                        + " > C:\\BotMaker\\s.log 2>&1"),
                ws.powerShellCommand(vmx, "botmaker", "pw", "C:\\BotMaker\\s"));
        assertEquals("\\\\vmware-host\\Shared Folders\\game", VmwareWorkstation.sharedFolderInGuest("game"));
        assertEquals(List.of(ws.diskManager().toString(), "-c", "-s", "80GB", "-a", "lsilogic", "-t", "0", "d.vmdk"),
                ws.createDiskCommand(Path.of("d.vmdk"), 80));

        assertEquals(List.of(Path.of("C:\\VMs\\a\\a.vmx"), Path.of("D:\\b b\\b.vmx")),
                VmwareWorkstation.parseList("Total running VMs: 2\r\nC:\\VMs\\a\\a.vmx\r\nD:\\b b\\b.vmx\r\n"));
        assertEquals(List.of(), VmwareWorkstation.parseList("Total running VMs: 0\n"));

        // vmrun exits with its own code when the guest program fails, and says the guest's in its output.
        assertEquals(3010, VmwareWorkstation.guestExitCode(new com.botmaker.shared.Spawn.Completed(255,
                "Error: Guest program exited with non-zero exit code: 3010")).exitCode());
        assertEquals(0, VmwareWorkstation.guestExitCode(new com.botmaker.shared.Spawn.Completed(0, "")).exitCode());
    }

    @Test
    void aHostsDefaultSizeIsHalfItsMemoryAndCores() {
        VmSize big = VmSize.forHost(32_768, 16);
        assertEquals(8192, big.memoryMb());
        assertEquals(4, big.cpus());
        assertEquals(64, big.diskGb(), "Windows 11's minimum: the disk grows as it fills");
        VmSize small = VmSize.forHost(14_000, 2);
        assertEquals(6144, small.memoryMb(), "half of 14 GB, in whole gigabytes");
        assertEquals(1, small.cpus());
        assertEquals(4096, VmSize.forHost(6000, 4).memoryMb());
        assertThrows(IllegalArgumentException.class,
                () -> new VmSpec("a/b", Path.of("vm"), new VmSize(1, 4096, 80), Path.of("w.iso"), List.of(), 5900));
        assertThrows(IllegalArgumentException.class, () -> new VmSize(2, 2048, 80), "below Windows 11's 4 GB");
    }
}
