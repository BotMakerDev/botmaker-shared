package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.emulator.WindowsRegistry;
import com.botmaker.shared.platform.Os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * QEMU on the Windows Hypervisor Platform, which Windows Home has: installed silently from winget, and run
 * without a window, its screen served over VNC on loopback and controlled through QMP ({@link QmpClient}).
 * The guest draws in software, so it suits 2D and click-driven games.
 *
 * <p>{@link #find()} never throws, and is empty off Windows or without QEMU.
 */
public record Qemu(Path folder) {

    public static final String WINGET_ID = "SoftwareFreedomConservancy.QEMU";
    private static final Duration INSTALL = Duration.ofMinutes(10);
    private static final Duration QUICK = Duration.ofSeconds(30);
    /** The UEFI firmware QEMU ships, its code read-only and its variables copied once per VM. */
    private static final String FIRMWARE_CODE = "edk2-x86_64-code.fd";
    private static final String FIRMWARE_VARS = "edk2-i386-vars.fd";

    /** The loopback ports one VM uses besides its screen's ({@link VmSpec#vncPort()}): QMP and the guest agent's. */
    public record Ports(int qmp, int agent) {

        public Ports {
            if (qmp < 1024 || qmp > 65535 || agent < 1024 || agent > 65535 || qmp == agent) {
                throw new IllegalArgumentException("Two different ports from 1024 up: " + qmp + ", " + agent);
            }
        }
    }

    /** The install that has {@code qemu-system-x86_64.exe}: its registry key, else the default folder. */
    public static Optional<Qemu> find() {
        if (!Os.current().isWindows()) return Optional.empty();
        List<String> candidates = new ArrayList<>();
        String registered = WindowsRegistry.read("HKLM\\SOFTWARE\\QEMU", "Install_Dir");
        if (registered != null && !registered.isBlank()) candidates.add(registered);
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) candidates.add(programFiles + "\\qemu");
        for (String dir : candidates) {
            try {
                Path folder = Path.of(dir);
                if (Files.isRegularFile(folder.resolve("qemu-system-x86_64.exe"))) return Optional.of(new Qemu(folder));
            } catch (RuntimeException e) {
                // a registry value that is no path
            }
        }
        return Optional.empty();
    }

    /** Installs QEMU from winget; its installer asks for administrator rights once. */
    public static Spawn.Completed install() throws IOException, InterruptedException {
        return Commands.run(INSTALL, installCommand());
    }

    static List<String> installCommand() {
        return List.of("winget", "install", "-e", "--id", WINGET_ID, "--silent",
                "--accept-package-agreements", "--accept-source-agreements");
    }

    public Path system() {
        return folder.resolve("qemu-system-x86_64.exe");
    }

    public Path img() {
        return folder.resolve("qemu-img.exe");
    }

    /** Creates a growable qcow2 disk of {@code gigabytes} at {@code disk}. */
    public Spawn.Completed createDisk(Path disk, int gigabytes) throws IOException, InterruptedException {
        return Commands.run(QUICK, img().toString(), "create", "-f", "qcow2", disk.toString(), gigabytes + "G");
    }

    /** Copies the firmware's variable store into {@code vmFolder}, once: it is where the guest's boot entries live. */
    public Path firmwareVars(Path vmFolder) throws IOException {
        Path vars = vmFolder.resolve("efivars.fd");
        // A plain copy: the shipped file may be read-only, and the guest writes this one.
        if (!Files.exists(vars)) Files.copy(folder.resolve("share").resolve(FIRMWARE_VARS), vars);
        return vars;
    }

    /**
     * The command that runs {@code spec} from {@code disk}:
     * <ul>
     *   <li>the Hypervisor Platform, else QEMU's own emulator (slow, but it starts);</li>
     *   <li>q35 with UEFI, every CPU feature the host offers (Windows 11 needs SSE4.2 and POPCNT);</li>
     *   <li>the disk on NVMe and an e1000e network card, which Windows Setup has drivers for;</li>
     *   <li>a USB tablet, so a VNC pointer lands where it is sent rather than drifting;</li>
     *   <li>the Windows disc then {@code spec.discs()} on SATA;</li>
     *   <li>VNC on loopback with a password, set over QMP once it starts; QMP and the guest agent's channel on
     *       loopback; no window.</li>
     * </ul>
     * The Windows disc boots first, and its loader waits five seconds for a key before it gives up: whoever
     * starts the VM presses one over VNC.
     */
    public List<String> command(VmSpec spec, Path disk, Path firmwareVars, Ports ports) {
        int vnc = spec.vncPort();
        if (vnc < 5900 || vnc > 5999) throw new IllegalArgumentException("QEMU serves VNC on 5900–5999: " + vnc);
        if (vnc == ports.qmp() || vnc == ports.agent()) throw new IllegalArgumentException("VNC's port is taken: " + vnc);
        List<String> c = new ArrayList<>(List.of(
                system().toString(),
                "-name", "botmaker-" + spec.name(),
                "-accel", "whpx,kernel-irqchip=off", "-accel", "tcg",
                "-machine", "q35",
                "-cpu", "max",
                "-smp", Integer.toString(spec.size().cpus()),
                "-m", spec.size().memoryMb() + "M",
                "-rtc", "base=localtime",
                "-drive", "if=pflash,format=raw,readonly=on,file=" + drivePath(folder.resolve("share").resolve(FIRMWARE_CODE)),
                "-drive", "if=pflash,format=raw,file=" + drivePath(firmwareVars),
                "-drive", "if=none,id=disk0,format=qcow2,file=" + drivePath(disk),
                "-device", "nvme,drive=disk0,serial=botmaker",
                "-netdev", "user,id=net0",
                "-device", "e1000e,netdev=net0",
                "-device", "qemu-xhci",
                "-device", "usb-tablet",
                "-vga", "std"));
        List<Path> discs = new ArrayList<>();
        if (spec.windowsIso() != null) discs.add(spec.windowsIso());
        discs.addAll(spec.discs());
        if (discs.size() > 6) throw new IllegalArgumentException("q35 has six SATA ports, for " + discs.size() + " discs.");
        for (int i = 0; i < discs.size(); i++) {
            String id = "cd" + i;
            c.addAll(List.of("-drive", "if=none,id=" + id + ",media=cdrom,readonly=on,file=" + drivePath(discs.get(i)),
                    "-device", "ide-cd,drive=" + id + ",bus=ide." + i));
        }
        c.addAll(List.of(
                "-vnc", String.format(Locale.ROOT, "127.0.0.1:%d,password=on", vnc - 5900),
                "-qmp", "tcp:127.0.0.1:" + ports.qmp() + ",server=on,wait=off",
                "-chardev", "socket,id=agent0,host=127.0.0.1,port=" + ports.agent() + ",server=on,wait=off",
                "-device", "virtio-serial-pci",
                "-device", "virtserialport,chardev=agent0,name=org.qemu.guest_agent.0",
                "-display", "none"));
        return List.copyOf(c);
    }

    /** A path inside a {@code -drive} option, where a comma would end the value unless doubled. */
    static String drivePath(Path path) {
        return path.toString().replace(",", ",,");
    }
}
