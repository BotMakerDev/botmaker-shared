package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.RunState;
import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.UserDirs;
import com.botmaker.shared.vnc.Keysyms;
import com.botmaker.shared.vnc.VncController;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets a game VM up, from a Windows ISO the user downloaded to a signed-in Windows desktop, with nobody at the
 * keyboard:
 * <ol>
 *   <li>{@link #problems}: what stops a VM here (no hypervisor, the Hypervisor Platform off for QEMU, too
 *       little disk or memory, no Windows disc);</li>
 *   <li>{@link #prepare}: its folder, passwords, answer disc, disk and configuration;</li>
 *   <li>{@link #install}: starts it on the Windows disc, presses the key the disc's loader waits for, and
 *       follows Windows Setup over VNC until the guest tools say the first sign-in is done (20–40 minutes).</li>
 * </ol>
 * Each step tells a {@link Listener} one sentence, and {@link #install} sends it the guest's screen as it goes.
 * {@link #install} picks up where it stopped: after Studio closed or the host rebooted, it starts the VM again
 * if needed and goes on waiting.
 */
public final class VmSetup {

    /** Hears a setup's progress; called on the setup's thread. */
    public interface Listener {
        void step(String sentence);

        default void frame(BufferedImage screen) {
        }
    }

    /** Where Microsoft serves the Windows 11 disc image; the user downloads it and picks the file. */
    public static final String WINDOWS_DOWNLOAD_PAGE = "https://www.microsoft.com/software-download/windows11";

    /**
     * virtio-win's guest tools: the drivers QEMU's devices want and the guest agent. Carried on the answer disc
     * and run at the first sign-in.
     */
    static final Downloads.Remote VIRTIO_GUEST_TOOLS = new Downloads.Remote(
            "https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/archive-virtio/virtio-win-0.1.302-1/"
                    + "virtio-win-guest-tools.exe",
            Downloads.Digest.SHA_256, "d8ae9ea1e943384ac195e012cd36f82c3161620e5fc9529be4e42e6f420e222d", 31_642_483);

    /** The guest's account. */
    public static final String GUEST_USER = "botmaker";
    /** What a game VM needs free on the disk that holds it: Windows, then a few games. */
    static final long MIN_FREE_GB = 40;
    private static final Duration BOOT_KEYS = Duration.ofSeconds(45);
    private static final Duration READY_POLL = Duration.ofSeconds(20);
    private static final Duration FRAME_EVERY = Duration.ofSeconds(5);
    private static final Duration QEMU_START = Duration.ofSeconds(30);
    private static final int AGENT_TIMEOUT_MS = 3_000;
    /**
     * A launch's agent calls: a first {@code guest-exec} just after a boot took over 3 s (measured), while the
     * same call later answers in about 100 ms.
     */
    private static final int LAUNCH_TIMEOUT_MS = 30_000;
    private static final int SPACE = 0x20;
    static final String QEMU_LOG = "qemu.log";
    /** Setup restarts Windows three or four times; the rest is room for faults. */
    static final int MAX_STARTS = 12;

    private VmSetup() {}

    /**
     * What stops setting up a {@code guestOs} VM of {@code size} with {@code hypervisor}, from {@code windowsIso}
     * for Windows (Linux downloads its own); empty when nothing.
     */
    public static List<String> problems(GuestOs guestOs, Hypervisor hypervisor, Path windowsIso, VmSize size) {
        List<String> problems = new ArrayList<>();
        if (guestOs == GuestOs.LINUX && hypervisor == Hypervisor.VMWARE) {
            // Its installer needs "autoinstall" on its kernel's command line, which only QEMU gives a disc's kernel.
            problems.add("A Linux game VM runs on QEMU for now.");
        }
        if (guestOs == GuestOs.UNKNOWN) problems.add("Pick Windows or Linux.");
        switch (hypervisor) {
            case VMWARE -> {
                if (VmwareWorkstation.find().isEmpty()) problems.add("VMware Workstation isn't installed.");
            }
            case QEMU -> {
                if (Qemu.find().isEmpty()) problems.add("QEMU isn't installed.");
                if (!hypervisorPlatformOn()) {
                    problems.add("The Windows Hypervisor Platform is off: turn it on, then restart Windows.");
                }
            }
            case UNKNOWN -> problems.add("Neither VMware Workstation nor QEMU is installed.");
        }
        if (guestOs == GuestOs.WINDOWS && (windowsIso == null || !Files.isRegularFile(windowsIso))) {
            problems.add("Pick the Windows disc image (.iso).");
        }
        try {
            Path root = VmInventory.root();
            Files.createDirectories(root);
            long freeGb = Files.getFileStore(root).getUsableSpace() >> 30;
            if (freeGb < MIN_FREE_GB) {
                problems.add("A game VM needs " + MIN_FREE_GB + " GB free on " + root.getRoot() + "; there are " + freeGb + ".");
            }
        } catch (IOException e) {
            problems.add("BotMaker can't write its VM folder: " + e.getMessage());
        }
        if (size.memoryMb() > VmSize.hostMemoryMb() - 2048) {
            problems.add("This computer has " + VmSize.hostMemoryMb() / 1024 + " GB of memory: give the VM less.");
        }
        return problems;
    }

    /** Whether the Windows Hypervisor Platform feature, which QEMU runs on, is turned on. */
    public static boolean hypervisorPlatformOn() {
        try {
            // Single quotes only: Java passes a double quote inside an argument through unescaped, and Windows drops it.
            Spawn.Completed state = Commands.run(Duration.ofSeconds(30), "powershell.exe", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_OptionalFeature -Filter 'Name=''HypervisorPlatform''').InstallState");
            return state.ok() && state.output().strip().equals("1");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Turns the Windows Hypervisor Platform on: Windows asks for administrator rights, and it takes effect after
     * a restart.
     */
    public static Spawn.Completed enableHypervisorPlatform() throws IOException, InterruptedException {
        return Commands.run(Duration.ofMinutes(10), "powershell.exe", "-NoProfile", "-Command",
                "Start-Process -Verb RunAs -Wait -FilePath dism.exe -ArgumentList "
                        + "'/online','/enable-feature','/featurename:HypervisorPlatform','/all','/norestart'");
    }

    /**
     * Makes the VM's folder, passwords, answer disc, disk and configuration, and records it as {@link
     * VmRecord.Stage#PREPARED}. A Windows VM installs from {@code windowsIso}; a Linux one downloads Ubuntu
     * ({@link LinuxAutoinstall#UBUNTU}, about 4 GB, kept for the next VM) and ignores it. Refuses a name already
     * used, and anything {@link #problems} names.
     */
    public static VmRecord prepare(String name, GuestOs guestOs, Path windowsIso, Hypervisor hypervisor, VmSize size,
                                   Listener listener) throws IOException, InterruptedException {
        try {
            VmSpec.requireName(name);
        } catch (IllegalArgumentException e) {
            throw new IOException("A VM's name is letters, digits, spaces, - and _, up to 41 characters.", e);
        }
        List<String> problems = problems(guestOs, hypervisor, windowsIso, size);
        if (!problems.isEmpty()) throw new IOException(problems.getFirst());
        Path folder = VmInventory.folder(name);
        if (Files.exists(folder.resolve(VmRecord.FILE))) throw new IOException("There is already a VM named " + name + ".");
        Files.createDirectories(folder);

        Path iso;
        String language;
        if (guestOs == GuestOs.LINUX) {
            iso = downloadUbuntu(listener);
            language = "en-US";
        } else {
            listener.step("Reading the Windows disc.");
            iso = windowsIso;
            language = isoLanguage(windowsIso);
        }
        int vnc = freeVncPort();
        int qmp = hypervisor == Hypervisor.QEMU ? freePort(List.of(vnc)) : 0;
        int agent = hypervisor == Hypervisor.QEMU ? freePort(List.of(vnc, qmp)) : 0;
        int events = hypervisor == Hypervisor.QEMU ? freePort(List.of(vnc, qmp, agent)) : 0;
        VmRecord vm = new VmRecord(folder, name, hypervisor, VmRecord.Stage.PREPARED, size, iso, language, vnc,
                qmp, agent, events, guestOs);

        VmCredentials credentials = VmCredentials.random();
        credentials.save(folder);

        if (guestOs == GuestOs.LINUX) {
            IsoImage.write(vm.answerIso(), LinuxAutoinstall.LABEL, LinuxAutoinstall.discFiles(GUEST_USER,
                    credentials.guest(), LinuxAutoinstall.hostname(name), hypervisor));
            listener.step("Taking the installer's start-up files off the Ubuntu disc.");
            IsoFiles.copy(iso, LinuxAutoinstall.KERNEL, folder.resolve(INSTALLER_KERNEL));
            IsoFiles.copy(iso, LinuxAutoinstall.INITRD, folder.resolve(INSTALLER_INITRD));
        } else {
            Map<String, byte[]> answer = new LinkedHashMap<>();
            answer.put("autounattend.xml", GuestUnattend.xml(GUEST_USER, credentials.guest(), computerName(name),
                    language, hypervisor).getBytes(StandardCharsets.UTF_8));
            if (hypervisor == Hypervisor.QEMU) {
                listener.step("Downloading the guest tools for QEMU (32 MB).");
                Path tools = UserDirs.cache().resolve("vm").resolve("virtio-win-guest-tools-0.1.302.exe");
                if (!Downloads.fetch(VIRTIO_GUEST_TOOLS, tools, Downloads.Progress.IGNORED)) {
                    throw new IOException("The guest tools for QEMU didn't download. Check the connection and try again.");
                }
                answer.put("virtio-win-guest-tools.exe", Files.readAllBytes(tools));
            }
            IsoImage.write(vm.answerIso(), "BOTMAKER", answer);
        }

        listener.step("Creating a disk of up to " + size.diskGb() + " GB; it takes only what " + guestOs.displayName()
                + " writes.");
        switch (hypervisor) {
            case QEMU -> {
                Qemu qemu = Qemu.find().orElseThrow(() -> new IOException("QEMU isn't installed."));
                Commands.require(qemu.createDisk(vm.disk(), size.diskGb()), "QEMU couldn't create the disk");
                qemu.firmwareVars(folder);
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find()
                        .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
                Commands.require(ws.createDisk(vm.disk(), size.diskGb()), "VMware couldn't create the disk");
                VmxFile.create(vm.spec(List.of(ws.toolsIso())), vm.disk().getFileName().toString(), credentials.vnc())
                        .write(vm.vmx());
            }
            case UNKNOWN -> throw new IOException("Neither VMware Workstation nor QEMU is installed.");
        }
        vm.save();
        return vm;
    }

    /** The Ubuntu installer's kernel and initial RAM disk, copied off its disc into the VM's folder. */
    static final String INSTALLER_KERNEL = "installer-vmlinuz";
    static final String INSTALLER_INITRD = "installer-initrd";

    /**
     * Ubuntu's disc, downloaded into the cache once from the first place that has it, and checked; a step each
     * tenth of the way. A disc already there is checked again (4 GB read, some seconds).
     */
    private static Path downloadUbuntu(Listener listener) throws IOException {
        Path iso = UserDirs.cache().resolve("vm").resolve(LinuxAutoinstall.UBUNTU_FILE);
        Files.createDirectories(iso.getParent());
        if (Files.exists(iso)) {
            listener.step("Checking the Ubuntu disc downloaded before.");
            if (Downloads.matches(iso, LinuxAutoinstall.UBUNTU.getFirst())) return iso;
            Files.delete(iso); // not the disc: fetch would read all of it again before replacing it
        }
        listener.step("Downloading Ubuntu Server (about 4 GB).");
        for (Downloads.Remote from : LinuxAutoinstall.UBUNTU) {
            int[] tenth = {0};
            boolean fetched = Downloads.fetch(from, iso, (bytes, total) -> {
                int now = total > 0 ? (int) (bytes * 10 / total) : 0;
                if (now > tenth[0]) {
                    tenth[0] = now;
                    listener.step("Downloading Ubuntu Server: " + now * 10 + "%.");
                }
            });
            if (fetched) return iso;
        }
        throw new IOException("Ubuntu didn't download. Check the connection and try again.");
    }

    /** Written when a Linux VM's installer has finished and restarted it: its disk then boots the system. */
    static final String INSTALLER_DONE = "installer-done";

    /**
     * The kernel QEMU boots for {@code vm}: a Linux VM's installer's, with {@code autoinstall}, until the installer
     * has finished ({@value #INSTALLER_DONE}); after that, and for Windows, the disk's own system. A start that
     * ended halfway through boots the installer again, which starts over on a wiped disk: the disk's half-written
     * system wouldn't boot, and the disc's own menu would boot the installer without {@code autoinstall}, which
     * then waits for a "yes".
     */
    static Optional<Qemu.Kernel> installerKernel(VmRecord vm) {
        if (vm.guestOs() != GuestOs.LINUX || vm.stage() == VmRecord.Stage.READY
                || Files.exists(vm.folder().resolve(INSTALLER_DONE))) {
            return Optional.empty();
        }
        return Optional.of(new Qemu.Kernel(vm.folder().resolve(INSTALLER_KERNEL), vm.folder().resolve(INSTALLER_INITRD),
                LinuxAutoinstall.KERNEL_ARGUMENTS));
    }

    /**
     * Installs {@code vm}'s system, or goes on waiting for it: starts the VM if it isn't running, presses the key
     * the Windows disc waits for on its first start (a Linux VM's installer starts by itself,
     * {@link #installerKernel}), then follows the installer until the guest says it is done. Records
     * {@link VmRecord.Stage#READY} and returns the record.
     * <p>
     * A QEMU VM is started again each time the guest restarts it ({@link Qemu#command} ends QEMU then, which exits
     * with 0), and when its processor stopped on a fault; the installer goes on as after any restart. At most
     * {@link #MAX_STARTS} starts. Throws when QEMU ends otherwise (a crash, or someone ended it), when a VMware VM
     * stops, or when {@code timeout} passes.
     */
    public static VmRecord install(VmRecord vm, Listener listener, Duration timeout)
            throws IOException, InterruptedException {
        if (vm.stage() == VmRecord.Stage.READY) return vm;
        VmCredentials credentials = VmCredentials.load(vm.folder())
                .orElseThrow(() -> new IOException("The VM's passwords are missing; set it up again."));
        long deadline = System.nanoTime() + timeout.toNanos();
        String os = vm.guestOs().displayName();
        listener.step(vm.stage() == VmRecord.Stage.PREPARED ? "Starting the VM on the " + os + " disc."
                : "Starting the VM again.");
        for (int starts = 1; ; starts++) {
            if (System.nanoTime() >= deadline) throw new IOException(tooLong(vm, timeout));
            Attempt attempt = installOnce(vm, credentials, starts == 1, listener, deadline, timeout);
            vm = attempt.vm();
            switch (attempt.ended()) {
                case READY -> {
                    return vm;
                }
                case RESTARTED -> listener.step(os + " restarted the VM: starting it again.");
                case FAULTED -> listener.step("The VM's processor stopped on a fault: starting it again.");
                case SCREEN_LOST -> listener.step("Connecting to the VM's screen again.");
            }
            if (starts >= MAX_STARTS) {
                throw new IOException("The VM was started " + starts + " times and " + os + " still isn't installed."
                        + logTail(vm));
            }
        }
    }

    private static String tooLong(VmRecord vm, Duration timeout) {
        return vm.guestOs().displayName() + " didn't finish installing within " + timeout.toMinutes()
                + " minutes. The VM is still running: look at its screen.";
    }

    /** How one start of an install ended. */
    private enum Ended { READY, RESTARTED, FAULTED, SCREEN_LOST }

    /** How one start of an install ended, and {@code vm} as it now stands. */
    private record Attempt(VmRecord vm, Ended ended) {
    }

    /**
     * Follows one start of the VM until the guest is ready, QEMU ends because the guest restarted, QEMU stopped
     * its processor (ended here too), or the screen's connection dropped with the VM still running. The key the
     * Windows disc waits for is pressed while the disk is still empty: once Setup has written to it, the disc
     * would start Setup over.
     */
    private static Attempt installOnce(VmRecord vm, VmCredentials credentials, boolean first, Listener listener,
                                       long deadline, Duration timeout) throws IOException, InterruptedException {
        boolean pressKey = vm.guestOs() == GuestOs.WINDOWS && !diskWritten(vm);
        String os = vm.guestOs().displayName();
        try (Running running = start(vm.withStage(VmRecord.Stage.INSTALLING), credentials)) {
            VmRecord installing = running.vm();
            VncController screen = running.screen();
            installing.save();
            if (pressKey) {
                listener.step("Pressing the key the Windows disc waits for.");
                // VMware's firmware takes a key during its start-up screen as a call for its Boot Manager, where
                // the VM then stays: there, the keys wait for the disc's prompt itself.
                if (installing.hypervisor() == Hypervisor.VMWARE) awaitBootPrompt(screen);
                long keysUntil = System.nanoTime() + BOOT_KEYS.toNanos();
                while (System.nanoTime() < keysUntil) {
                    screen.keyDown(SPACE);
                    screen.keyUp(SPACE);
                    Thread.sleep(500);
                }
            }
            if (first) {
                listener.step(vm.guestOs() == GuestOs.LINUX
                        ? "Ubuntu is installing, then Steam and Legendary: this takes 15 to 30 minutes."
                        : "Windows is installing: this takes 20 to 40 minutes, and the VM restarts a few times.");
            }
            long nextCheck = 0;
            while (System.nanoTime() < deadline) {
                BufferedImage frame = screen.captureScreen();
                if (frame != null) listener.frame(frame);
                boolean screenLost = !screen.alive();
                // A screen gone is most often QEMU gone: look now rather than at the next check.
                if (System.nanoTime() >= nextCheck || screenLost) {
                    nextCheck = System.nanoTime() + READY_POLL.toNanos();
                    if (!VmInventory.running(installing)) {
                        if (installing.hypervisor() != Hypervisor.QEMU) {
                            throw new IOException("The VM stopped while " + os + " was installing." + logTail(installing));
                        }
                        awaitExit(installing, running.qemu());
                        // Unknown when an earlier run started it: a restart is the likely end.
                        int exit = running.qemu().map(Process::exitValue).orElse(0);
                        if (exit != 0) {
                            throw new IOException("QEMU ended (exit code " + exit + ") while " + os + " was installing."
                                    + logTail(installing));
                        }
                        // Ubuntu's installer restarts the VM once, when it is done; its system never does before
                        // it is ready.
                        if (installing.guestOs() == GuestOs.LINUX) {
                            Files.writeString(installing.folder().resolve(INSTALLER_DONE), "");
                        }
                        return new Attempt(installing, Ended.RESTARTED);
                    }
                    if (faulted(installing)) {
                        stopQemu(installing, running.qemu());
                        return new Attempt(installing, Ended.FAULTED);
                    }
                    if (screenLost) return new Attempt(installing, Ended.SCREEN_LOST);
                    if (guestReady(installing, credentials)) {
                        VmRecord ready = installing.withStage(VmRecord.Stage.READY);
                        ready.save();
                        removeAnswerDisc(ready);
                        listener.step(os + " is installed" + (vm.guestOs() == GuestOs.WINDOWS ? " and signed in." : "."));
                        return new Attempt(ready, Ended.READY);
                    }
                }
                Thread.sleep(FRAME_EVERY.toMillis());
            }
            throw new IOException(tooLong(vm, timeout));
        }
    }

    /** How long a VM's firmware may take to show the Windows disc's prompt. */
    private static final Duration BOOT_PROMPT = Duration.ofSeconds(60);

    /**
     * Waits until {@code screen} shows the Windows disc's "Press any key to boot from CD or DVD" or
     * {@link #BOOT_PROMPT} has passed; the keys go either way.
     */
    private static void awaitBootPrompt(VncController screen) throws InterruptedException {
        long until = System.nanoTime() + BOOT_PROMPT.toNanos();
        while (System.nanoTime() < until && screen.alive()) {
            BufferedImage frame = screen.captureScreen();
            if (frame != null && looksLikeBootPrompt(frame)) return;
            Thread.sleep(250);
        }
    }

    /** The share of the screen's height the boot prompt's line sits in, from the top. */
    private static final double PROMPT_BAND = 0.08;

    /**
     * Whether {@code frame} is the Windows disc's boot prompt: a line of text at the top and nothing else, live
     * on VMware at 1024x768. Its start-up screen (a logo in the middle) and Boot Manager (a menu in the middle)
     * are not.
     */
    static boolean looksLikeBootPrompt(BufferedImage frame) {
        int band = (int) (frame.getHeight() * PROMPT_BAND);
        boolean text = false;
        for (int y = 0; y < frame.getHeight(); y += 2) {
            for (int x = 0; x < frame.getWidth(); x += 2) {
                int rgb = frame.getRGB(x, y);
                boolean lit = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF) > 96;
                if (!lit) continue;
                if (y >= band) return false;
                text = true;
            }
        }
        return text;
    }

    /**
     * Whether Setup has written to {@code vm}'s disk. A new one holds only its own tables: a few hundred KB for
     * qcow2, 78 MB for VMware's 64 GB sparse disk (live). Setup writes gigabytes within minutes.
     */
    static boolean diskWritten(VmRecord vm) throws IOException {
        return Files.exists(vm.disk()) && Files.size(vm.disk()) > 512L * 1024 * 1024;
    }

    /**
     * Ejects and deletes the answer disc of a VM whose system is installed: it holds the guest's password in plain
     * text, and no later start attaches it. A Linux installer's kernel goes too. Never throws: the system is
     * installed whatever happens here, and {@link #start} tries again when the VM is next started.
     */
    private static void removeAnswerDisc(VmRecord ready) {
        try {
            ejectDiscs(ready);
        } catch (IOException e) {
            // QEMU holds the file until it ends; start() deletes it then.
        }
        deleteInstallFiles(ready);
    }

    /** {@code vm}'s answer disc and installer kernel, which only its install needs; quietly, as above. */
    private static void deleteInstallFiles(VmRecord vm) {
        for (Path file : List.of(vm.answerIso(), vm.folder().resolve(INSTALLER_KERNEL),
                vm.folder().resolve(INSTALLER_INITRD), vm.folder().resolve(INSTALLER_DONE))) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // QEMU holds it until it ends
            }
        }
    }

    /**
     * Whether QEMU stopped {@code vm}'s processor: with the Hypervisor Platform, a fault it can't hand the guest
     * pauses the VM ("WHPX: Unexpected VP exit code 4" in its log), seen on a restart inside QEMU. Nothing else
     * pauses a VM BotMaker installs. Only QEMU's; VMware reports none.
     */
    static boolean faulted(VmRecord vm) {
        if (vm.hypervisor() != Hypervisor.QEMU) return false;
        try (QmpClient qmp = QmpClient.connect(vm.qmpPort())) {
            return switch (qmp.status()) {
                case PAUSED, INTERNAL_ERROR, GUEST_PANICKED -> true;
                case RUNNING, SHUTDOWN, UNKNOWN -> false;
            };
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Takes the discs out of a running QEMU VM's drives, so their files can be deleted or moved; a stopped VMware
     * VM leaves its own at its next start ({@link #start}).
     */
    private static void ejectDiscs(VmRecord vm) throws IOException {
        if (vm.hypervisor() != Hypervisor.QEMU) return;
        // The drives QEMU was started with: the record is READY now, and its spec would list none.
        int drives = Qemu.cdDrives(vm.withStage(VmRecord.Stage.INSTALLING).spec(List.of()));
        try (QmpClient qmp = QmpClient.connect(vm.qmpPort())) {
            for (int i = 0; i < drives; i++) {
                // force: Windows locks the tray of a drive it has mounted.
                qmp.execute("eject", Map.of("id", Qemu.cdDrive(i), "force", true));
            }
        }
    }

    /** Ends {@code vm}'s QEMU, if it still runs, and waits until it has gone. */
    private static void stopQemu(VmRecord vm, Optional<Process> qemu) throws IOException, InterruptedException {
        if (QmpClient.listening(vm.qmpPort())) {
            try (QmpClient qmp = QmpClient.connect(vm.qmpPort())) {
                qmp.quit();
            } catch (IOException e) {
                // gone between the two looks
            }
        }
        awaitExit(vm, qemu);
    }

    /**
     * Waits until {@code vm}'s QEMU has exited: its process when this run started it, else its QMP port. The port
     * closes before the process has let go of the disk, which the next QEMU must open.
     */
    private static void awaitExit(VmRecord vm, Optional<Process> qemu) throws IOException, InterruptedException {
        if (qemu.isPresent()) {
            if (!qemu.get().waitFor(QEMU_START.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("QEMU didn't stop." + logTail(vm));
            }
            return;
        }
        long until = System.nanoTime() + QEMU_START.toNanos();
        while (QmpClient.listening(vm.qmpPort())) {
            if (System.nanoTime() > until) throw new IOException("QEMU didn't stop." + logTail(vm));
            Thread.sleep(500);
        }
    }

    /**
     * A started VM and its screen. {@link #vm()} is the record as it now stands: a start may have moved its
     * ports, and saved that. {@link #qemu()} is the QEMU process this start launched; empty for VMware, and for a
     * VM that was already running.
     */
    public record Running(VmRecord vm, VncController screen, Optional<Process> qemu) implements AutoCloseable {

        /** Disconnects from the screen; the VM goes on running. */
        @Override
        public void close() {
            screen.close();
        }
    }

    /**
     * Starts {@code vm} without a window if it isn't running, and connects to its screen.
     * <ul>
     *   <li>QEMU: ports that something else took since the last start (Windows reserves port ranges again at
     *       each boot) are chosen again and saved. Its VNC password is given over QMP, as its command line
     *       can't carry one.</li>
     *   <li>VMware: its {@code .vmx} is brought in line with the record first: the VNC port, and the discs, so a
     *       VM whose Windows is installed no longer carries the installer or the answer disc.</li>
     * </ul>
     */
    public static Running start(VmRecord vm, VmCredentials credentials) throws IOException, InterruptedException {
        Optional<Process> started = Optional.empty();
        switch (vm.hypervisor()) {
            case QEMU -> {
                Qemu qemu = Qemu.find().orElseThrow(() -> new IOException("QEMU isn't installed."));
                if (!QmpClient.listening(vm.qmpPort())) {
                    vm = withFreePorts(vm);
                    if (vm.stage() == VmRecord.Stage.READY) deleteInstallFiles(vm); // left by install()
                    Path log = vm.folder().resolve(QEMU_LOG);
                    // Appended, one header per start: an install starts QEMU several times, and why the earlier
                    // ones ended is the evidence.
                    Files.writeString(log, "--- " + java.time.LocalDateTime.now().withNano(0) + " start\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    started = Optional.of(new ProcessBuilder(qemu.command(vm.spec(List.of()), vm.disk(),
                            vm.folder().resolve("efivars.fd"), vm.qemuPorts(), installerKernel(vm)))
                            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                            .redirectErrorStream(true)
                            .start());
                    long until = System.nanoTime() + QEMU_START.toNanos();
                    while (!QmpClient.listening(vm.qmpPort())) {
                        if (System.nanoTime() > until || !started.get().isAlive()) {
                            throw new IOException("QEMU didn't start." + logTail(vm));
                        }
                        Thread.sleep(500);
                    }
                }
                try (QmpClient qmp = QmpClient.connect(vm.qmpPort())) {
                    qmp.setVncPassword(credentials.vnc());
                }
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find()
                        .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
                if (!VmInventory.running(vm)) {
                    if (!bindable(vm.vncPort())) {
                        vm = vm.withPorts(freeVncPort(), 0, 0, 0);
                        vm.save();
                    }
                    VmxFile.read(vm.vmx()).setDiscs(vm.spec(List.of(ws.toolsIso())))
                            .set("RemoteDisplay.vnc.port", Integer.toString(vm.vncPort()))
                            .write(vm.vmx());
                    Commands.require(ws.start(vm.vmx()), "VMware couldn't start the VM");
                }
            }
            case UNKNOWN -> throw new IOException("This VM's hypervisor is unknown.");
        }
        VncController screen = VncController.connect("127.0.0.1", vm.vncPort(), credentials.vnc(),
                Keysyms.NativeKeys.VIRTUAL_KEY, vm.name(),
                vm.guestOs() == GuestOs.WINDOWS ? GuestWindows.reader(vm, credentials) : List::of, 30_000);
        return new Running(vm, screen, started);
    }

    /**
     * {@code vm} with any of its QEMU ports that can't be bound now chosen again, and saved when one moved. A VM
     * recorded before it had an events port is given one here.
     */
    private static VmRecord withFreePorts(VmRecord vm) throws IOException {
        int vnc = bindable(vm.vncPort()) ? vm.vncPort() : freeVncPort();
        int qmp = bindable(vm.qmpPort()) && vm.qmpPort() != vnc ? vm.qmpPort() : freePort(List.of(vnc));
        int agent = bindable(vm.agentPort()) && vm.agentPort() != vnc && vm.agentPort() != qmp
                ? vm.agentPort() : freePort(List.of(vnc, qmp));
        int events = vm.eventsPort() != 0 && bindable(vm.eventsPort()) && !List.of(vnc, qmp, agent).contains(vm.eventsPort())
                ? vm.eventsPort() : freePort(List.of(vnc, qmp, agent));
        if (vnc == vm.vncPort() && qmp == vm.qmpPort() && agent == vm.agentPort() && events == vm.eventsPort()) return vm;
        VmRecord moved = vm.withPorts(vnc, qmp, agent, events);
        moved.save();
        return moved;
    }

    /** How long Windows gets to shut down before the VM is powered off. */
    private static final Duration SHUT_DOWN = Duration.ofMinutes(3);

    /**
     * Shuts {@code vm} down as its power button would and waits until it has stopped; when Windows hasn't
     * finished within {@link #SHUT_DOWN} (an app holding it up), powers it off. Nothing happens when it isn't
     * running. A bot running in it ends: its session hears that Windows shut down, which isn't a restart.
     *
     * @return whether Windows shut down by itself; {@code false} when it had to be powered off
     */
    public static boolean shutDown(VmRecord vm) throws IOException, InterruptedException {
        if (!VmInventory.running(vm)) return true;
        switch (vm.hypervisor()) {
            case QEMU -> {
                try (QmpClient qmp = QmpClient.connect(vm.qmpPort())) {
                    qmp.powerDown();
                }
                long until = System.nanoTime() + SHUT_DOWN.toNanos();
                boolean clean = true;
                while (QmpClient.listening(vm.qmpPort())) {
                    if (System.nanoTime() > until) {
                        stopQemu(vm, Optional.empty());
                        clean = false;
                        break;
                    }
                    Thread.sleep(1_000);
                }
                awaitDiskFree(vm);
                return clean;
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find()
                        .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
                if (ws.stop(vm.vmx(), false).ok()) return true;
                Commands.require(ws.stop(vm.vmx(), true), "VMware couldn't power the VM off");
                return false;
            }
            default -> throw new IOException("This VM's hypervisor is unknown.");
        }
    }

    /**
     * Waits until the QEMU that ran {@code vm} has let go of its disk: its QMP port closes before that, and a
     * start in between would find the disk taken. Gives up quietly after {@link #QEMU_START}, as the start then
     * says why it can't.
     */
    private static void awaitDiskFree(VmRecord vm) throws InterruptedException {
        long until = System.nanoTime() + QEMU_START.toNanos();
        while (System.nanoTime() < until) {
            try (java.nio.channels.FileChannel disk = java.nio.channels.FileChannel.open(vm.disk(),
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
                 java.nio.channels.FileLock lock = disk.tryLock()) {
                if (lock != null) return;
            } catch (IOException e) {
                // still held
            }
            Thread.sleep(500);
        }
    }

    /**
     * Runs {@code command}, a Windows command line ({@link GuestLaunch#command}), on the guest's signed-in desktop
     * and returns without waiting for it. Both write it into the launch script, and start the launch task, which
     * runs it on the desktop. QEMU through the guest agent; VMware through vmrun, which also creates the task in a
     * VM that lacks it ({@link GuestUnattend#launchTaskScript}). Two VMware ways that failed live: the command on
     * vmrun's own command line came out with its quotes mangled ({@code start "" x} opened a console titled
     * {@code x}), and a program {@code runProgramInGuest -interactive} starts is ended with everything it started
     * once it exits, so whatever {@code start} handed off was gone at once.
     *
     * <p>It returns once the script has run its command: the script's last act deletes
     * {@value GuestUnattend#LAUNCH_PENDING}, which is written before the task starts. A second command written
     * before then took the first one's place, live (the task reads the script when it gets to it). One command at a
     * time per JVM.
     *
     * @throws IOException when the desktop hasn't run it within {@link #LAUNCH_RAN}, as before the user's desktop
     *                     is up
     */
    public static void runOnDesktop(VmRecord vm, VmCredentials credentials, String command)
            throws IOException, InterruptedException {
        requireWindows(vm);
        synchronized (LAUNCHING) {
            switch (vm.hypervisor()) {
                case QEMU -> {
                    try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), LAUNCH_TIMEOUT_MS)) {
                        agent.writeFile(GuestUnattend.LAUNCH_PENDING, new byte[0]);
                        agent.writeFile(GuestUnattend.LAUNCH_SCRIPT, GuestLaunch.script(command));
                        agent.runLaunchTask();
                        awaitLaunched(() -> agent.fileExists(GuestUnattend.LAUNCH_PENDING));
                    }
                }
                case VMWARE -> runOnVmwareDesktop(vm, credentials, command);
                case UNKNOWN -> throw new IOException("This VM's hypervisor is unknown.");
            }
        }
    }

    /** One launch at a time: they share the guest's launch script. */
    private static final Object LAUNCHING = new Object();
    /** How long the guest's desktop gets to run a launch script once its task is started. */
    static final Duration LAUNCH_RAN = Duration.ofSeconds(20);

    private interface Pending {
        boolean still() throws IOException, InterruptedException;
    }

    private static void awaitLaunched(Pending pending) throws IOException, InterruptedException {
        long until = System.nanoTime() + LAUNCH_RAN.toNanos();
        while (pending.still()) {
            if (System.nanoTime() > until) {
                throw new IOException("The VM's desktop didn't run the launch within " + LAUNCH_RAN.toSeconds()
                        + " s: is Windows signed in there?");
            }
            Thread.sleep(250);
        }
    }

    /** {@link #runOnDesktop} for VMware: the launch task's script marks the launch pending, then starts it. */
    private static void runOnVmwareDesktop(VmRecord vm, VmCredentials credentials, String command)
            throws IOException, InterruptedException {
        VmwareWorkstation ws = VmwareWorkstation.find()
                .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
        Path script = Files.createTempFile("botmaker-launch", ".cmd");
        try {
            Files.write(script, GuestLaunch.script(command));
            Commands.require(ws.copyToGuest(vm.vmx(), GUEST_USER, credentials.guest(), script,
                    GuestUnattend.LAUNCH_SCRIPT), "VMware couldn't put the launch script in the VM");
        } finally {
            Files.deleteIfExists(script);
        }
        Spawn.Completed ran = ws.runPowerShell(vm.vmx(), GUEST_USER, credentials.guest(),
                GuestUnattend.launchTaskScript(), LAUNCH_TASK_RUN);
        if (!ran.ok()) {
            throw new IOException("VMware couldn't start the game in the VM (exit code " + ran.exitCode() + ")"
                    + (ran.output().isBlank() ? "." : ": " + lastLines(ran.output(), 2)));
        }
        awaitLaunched(() -> ws.fileExistsInGuest(vm.vmx(), GUEST_USER, credentials.guest(),
                GuestUnattend.LAUNCH_PENDING));
    }

    /**
     * Whether {@code spec} runs in the running {@code vm}'s guest ({@link GuestGame}), and with {@code stop}, ends
     * its processes first: the result then names what was ended, and is {@link RunState#RUNNING} when there
     * was something to end. {@link GuestGame.Found#UNKNOWN} for a target the guest can't tell apart.
     *
     * @throws IOException when the guest couldn't run the check
     */
    public static GuestGame.Found game(VmRecord vm, VmCredentials credentials, LaunchSpec spec, boolean stop)
            throws IOException, InterruptedException {
        requireWindows(vm);
        Optional<String> script = GuestGame.script(spec, stop);
        if (script.isEmpty()) return GuestGame.Found.UNKNOWN;
        GuestAgent.Ran ran = runPowerShell(vm, credentials, script.get(), GAME_CHECK);
        String doing = (stop ? "stop " : "look for ") + spec.describe();
        if (ran.exitCode() == Commands.TIMED_OUT) {
            throw new IOException("The VM took over " + GAME_CHECK.toSeconds() + " s to " + doing + ".");
        }
        if (ran.exitCode() != 0) {
            throw new IOException("The VM couldn't " + doing + " (exit code " + ran.exitCode() + ")"
                    + (ran.output().isBlank() ? "." : ": " + lastLines(ran.output(), 2)));
        }
        GuestGame.Found found = GuestGame.parse(ran.output());
        if (!found.survived().isEmpty()) {
            throw new IOException("The VM couldn't end " + String.join(", ", found.survived()) + ".");
        }
        return found;
    }

    /**
     * Runs PowerShell {@code script} in the running {@code vm}'s guest, without a desktop, and waits up to
     * {@code timeout}: through the guest agent as SYSTEM, or through vmrun elevated. A vmrun that timed out ends
     * with {@link Commands#TIMED_OUT}.
     *
     * @throws IOException when the agent's run outlasted {@code timeout}, or the guest couldn't be reached
     */
    private static GuestAgent.Ran runPowerShell(VmRecord vm, VmCredentials credentials, String script,
                                                Duration timeout) throws IOException, InterruptedException {
        return switch (vm.hypervisor()) {
            case QEMU -> {
                try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), LAUNCH_TIMEOUT_MS)) {
                    yield agent.run("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                            List.of("-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
                                    encoded(script)), timeout);
                }
            }
            case VMWARE -> {
                Spawn.Completed done = VmwareWorkstation.find()
                        .orElseThrow(() -> new IOException("VMware Workstation isn't installed."))
                        .runPowerShell(vm.vmx(), GUEST_USER, credentials.guest(), script, timeout);
                yield new GuestAgent.Ran(done.exitCode(), done.output());
            }
            case UNKNOWN -> throw new IOException("This VM's hypervisor is unknown.");
        };
    }

    /**
     * {@code script} as {@code powershell.exe -EncodedCommand} takes it (UTF-16LE, Base64), which needs no quoting
     * on the agent's command line.
     */
    static String encoded(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /** How long finding or stopping a game's processes in the guest may take. */
    private static final Duration GAME_CHECK = Duration.ofMinutes(1);

    /** How long starting the launch task through vmrun may take: a script copied in, run, and its log fetched. */
    private static final Duration LAUNCH_TASK_RUN = Duration.ofMinutes(1);
    /** How long a launcher's download and silent install may take in the guest. */
    private static final Duration LAUNCHER_INSTALL = Duration.ofMinutes(15);
    /** {@code msiexec}'s "done, a restart finishes it": installed, as far as a launcher cares. */
    private static final int MSI_RESTART_REQUIRED = 3010;

    /** Whether {@code launcher} is installed in the running {@code vm}'s guest. */
    public static boolean guestHas(VmRecord vm, VmCredentials credentials, GuestLauncher launcher)
            throws IOException, InterruptedException {
        if (launcher == GuestLauncher.UNKNOWN) return true;
        if (vm.guestOs() == GuestOs.LINUX) {
            LinuxDisplay.requireLinuxOnQemu(vm);
            try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), LAUNCH_TIMEOUT_MS)) {
                return agent.fileExists(launcher.linuxExecutable());
            }
        }
        requireWindows(vm);
        switch (vm.hypervisor()) {
            case QEMU -> {
                try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), LAUNCH_TIMEOUT_MS)) {
                    for (String path : launcher.executables()) {
                        if (agent.fileExists(path)) return true;
                    }
                }
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find()
                        .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
                // vmrun's file check reads every failure as "no such file": a guest whose Tools aren't up yet
                // would look as if it lacked the launcher.
                if (!ws.toolsRunning(vm.vmx())) throw new IOException("VMware Tools isn't answering in the VM yet.");
                for (String path : launcher.executables()) {
                    if (ws.fileExistsInGuest(vm.vmx(), GUEST_USER, credentials.guest(), path)) return true;
                }
            }
            case UNKNOWN -> throw new IOException("This VM's hypervisor is unknown.");
        }
        return false;
    }

    /**
     * Downloads {@code launcher}'s installer in the running {@code vm}'s guest and runs it silently, waiting for
     * it (a few minutes); the user then signs in through the VM's screen. The guest needs the internet, which both
     * hypervisors' NAT gives it.
     *
     * @throws IOException with a sentence when the installer failed, or the launcher isn't there after it
     */
    public static void installInGuest(VmRecord vm, VmCredentials credentials, GuestLauncher launcher)
            throws IOException, InterruptedException {
        if (launcher == GuestLauncher.UNKNOWN) return;
        requireWindows(vm);
        GuestAgent.Ran ran = runPowerShell(vm, credentials, launcher.installScript(), LAUNCHER_INSTALL);
        if (ran.exitCode() == Commands.TIMED_OUT) {
            throw new IOException(launcher.displayName() + "'s installer was still running in the VM after "
                    + LAUNCHER_INSTALL.toMinutes() + " minutes.");
        }
        if (ran.exitCode() != 0 && ran.exitCode() != MSI_RESTART_REQUIRED) {
            String said = ran.output().strip();
            throw new IOException(launcher.displayName() + "'s installer failed in the VM (exit code " + ran.exitCode()
                    + ")" + (said.isEmpty() ? "." : ": " + lastLines(said, 3)));
        }
        if (!guestHas(vm, credentials, launcher)) {
            throw new IOException(launcher.displayName() + "'s installer finished, but it isn't at "
                    + String.join(" or ", launcher.executables()) + " in the VM.");
        }
    }

    /** The last {@code n} lines of {@code text}, joined with spaces: what a program said last is why it failed. */
    private static String lastLines(String text, int n) {
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        return String.join(" ", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    /**
     * Throws unless {@code vm} runs Windows: its desktop's launches, store launchers, game processes, window list
     * and game copies are Windows', and a Linux VM's come with its displays.
     */
    static void requireWindows(VmRecord vm) throws IOException {
        if (vm.guestOs() != GuestOs.WINDOWS) {
            throw new IOException("The game VM " + vm.name() + " runs " + vm.guestOs().displayName()
                    + ", which can't do this yet: it is for a Windows game VM.");
        }
    }

    /** Whether the guest tools answer and the answer file's last command has run. */
    public static boolean guestReady(VmRecord vm, VmCredentials credentials) throws IOException, InterruptedException {
        String ready = vm.guestOs() == GuestOs.LINUX ? LinuxAutoinstall.READY_FILE : GuestUnattend.READY_FILE;
        return switch (vm.hypervisor()) {
            case QEMU -> {
                try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
                    yield agent.fileExists(ready);
                } catch (IOException e) {
                    yield false;
                }
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find().orElse(null);
                yield ws != null && ws.toolsRunning(vm.vmx())
                        && ws.fileExistsInGuest(vm.vmx(), GUEST_USER, credentials.guest(), ready);
            }
            case UNKNOWN -> false;
        };
    }

    private static final Pattern LANGUAGE = Pattern.compile("(?m)^\\s*([a-z]{2,3}-[A-Za-z]{2,4})\\s*=");

    /**
     * The Windows disc's language, from its {@code sources\lang.ini}, read by mounting the disc. {@code en-US}
     * when the disc can't be read that way. Throws when the disc isn't a Windows installer.
     */
    static String isoLanguage(Path iso) throws IOException, InterruptedException {
        String path = iso.toAbsolutePath().toString().replace("'", "''");
        Spawn.Completed read = Commands.run(Duration.ofMinutes(2), "powershell.exe", "-NoProfile", "-Command",
                "$i = Mount-DiskImage -ImagePath '" + path + "' -PassThru; try { $d = ($i | Get-Volume).DriveLetter;"
                        + " if (-not (Test-Path ($d + ':\\sources\\setup.exe'))) { 'NOT-WINDOWS' }"
                        + " else { Get-Content ($d + ':\\sources\\lang.ini') } }"
                        + " finally { Dismount-DiskImage -ImagePath '" + path + "' | Out-Null }");
        if (read.output().contains("NOT-WINDOWS")) throw new IOException(iso.getFileName() + " isn't a Windows installer disc.");
        return read.ok() ? languageOf(read.output()) : "en-US";
    }

    /** The first language under {@code [Available UI Languages]} in a {@code lang.ini}. */
    static String languageOf(String langIni) {
        int section = langIni.indexOf("[Available UI Languages]");
        if (section < 0) return "en-US";
        String rest = langIni.substring(section);
        int next = rest.indexOf('[', 1);
        Matcher m = LANGUAGE.matcher(next < 0 ? rest : rest.substring(0, next));
        if (!m.find()) return "en-US";
        // As Windows writes a language tag; some discs list theirs in lower case.
        String[] parts = m.group(1).split("-", 2);
        return parts[0].toLowerCase(java.util.Locale.ROOT) + "-" + parts[1].toUpperCase(java.util.Locale.ROOT);
    }

    /** A Windows computer name from a VM's: at most 15 letters, digits and hyphens. */
    static String computerName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (cleaned.isEmpty() || cleaned.matches("\\d+")) cleaned = "BOTMAKER-" + cleaned;
        return cleaned.substring(0, Math.min(15, cleaned.length())).replaceAll("-$", "");
    }

    /** A VNC port on loopback, 5900–5999, that nothing listens on and no other VM was given. */
    static int freeVncPort() throws IOException {
        List<Integer> taken = VmInventory.portsInUse();
        for (int port = 5900; port <= 5999; port++) {
            if (!taken.contains(port) && bindable(port)) return port;
        }
        throw new IOException("Every VNC port from 5900 to 5999 is in use.");
    }

    static int freePort(List<Integer> alsoTaken) throws IOException {
        List<Integer> taken = new ArrayList<>(VmInventory.portsInUse());
        taken.addAll(alsoTaken);
        for (int attempt = 0; attempt < 20; attempt++) {
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                if (!taken.contains(s.getLocalPort())) return s.getLocalPort();
            }
        }
        throw new IOException("No free loopback port.");
    }

    private static boolean bindable(int port) {
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The end of QEMU's log, which says why it stopped; empty for VMware. */
    private static String logTail(VmRecord vm) {
        Path log = vm.folder().resolve(QEMU_LOG);
        try {
            if (!Files.isRegularFile(log)) return "";
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            List<String> tail = lines.subList(Math.max(0, lines.size() - 8), lines.size());
            return tail.isEmpty() ? "" : " QEMU said: " + String.join(" ", tail).strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
