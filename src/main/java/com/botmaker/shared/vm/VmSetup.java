package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
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

    /** What stops setting up a VM of {@code size} from {@code windowsIso} with {@code hypervisor}; empty when nothing. */
    public static List<String> problems(Hypervisor hypervisor, Path windowsIso, VmSize size) {
        List<String> problems = new ArrayList<>();
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
        if (windowsIso == null || !Files.isRegularFile(windowsIso)) problems.add("Pick the Windows disc image (.iso).");
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
     * VmRecord.Stage#PREPARED}. Refuses a name already used, and anything {@link #problems} names.
     */
    public static VmRecord prepare(String name, Path windowsIso, Hypervisor hypervisor, VmSize size,
                                   Listener listener) throws IOException, InterruptedException {
        try {
            VmSpec.requireName(name);
        } catch (IllegalArgumentException e) {
            throw new IOException("A VM's name is letters, digits, spaces, - and _, up to 41 characters.", e);
        }
        List<String> problems = problems(hypervisor, windowsIso, size);
        if (!problems.isEmpty()) throw new IOException(problems.getFirst());
        Path folder = VmInventory.folder(name);
        if (Files.exists(folder.resolve(VmRecord.FILE))) throw new IOException("There is already a VM named " + name + ".");
        Files.createDirectories(folder);

        listener.step("Reading the Windows disc.");
        String language = isoLanguage(windowsIso);
        int vnc = freeVncPort();
        int qmp = hypervisor == Hypervisor.QEMU ? freePort(List.of(vnc)) : 0;
        int agent = hypervisor == Hypervisor.QEMU ? freePort(List.of(vnc, qmp)) : 0;
        VmRecord vm = new VmRecord(folder, name, hypervisor, VmRecord.Stage.PREPARED, size, windowsIso, language, vnc,
                qmp, agent);

        VmCredentials credentials = VmCredentials.random();
        credentials.save(folder);

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

        listener.step("Creating a disk of up to " + size.diskGb() + " GB; it takes only what Windows writes.");
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

    /**
     * Installs Windows in {@code vm}, or goes on waiting for it: starts the VM if it isn't running, presses the
     * key the Windows disc waits for on its first start, then follows Setup until the guest says it is done.
     * Records {@link VmRecord.Stage#READY} and returns the record.
     * <p>
     * A QEMU VM is started again each time Windows restarts it ({@link Qemu#command} ends QEMU then, which exits
     * with 0), and when its processor stopped on a fault; Setup goes on as after any restart. At most
     * {@link #MAX_STARTS} starts. Throws when QEMU ends otherwise (a crash, or someone ended it), when a VMware VM
     * stops, or when {@code timeout} passes.
     */
    public static VmRecord install(VmRecord vm, Listener listener, Duration timeout)
            throws IOException, InterruptedException {
        if (vm.stage() == VmRecord.Stage.READY) return vm;
        VmCredentials credentials = VmCredentials.load(vm.folder())
                .orElseThrow(() -> new IOException("The VM's passwords are missing; set it up again."));
        long deadline = System.nanoTime() + timeout.toNanos();
        listener.step(vm.stage() == VmRecord.Stage.PREPARED ? "Starting the VM on the Windows disc." : "Starting the VM again.");
        for (int starts = 1; ; starts++) {
            if (System.nanoTime() >= deadline) throw new IOException(tooLong(timeout));
            Attempt attempt = installOnce(vm, credentials, starts == 1, listener, deadline, timeout);
            vm = attempt.vm();
            switch (attempt.ended()) {
                case READY -> {
                    return vm;
                }
                case RESTARTED -> listener.step("Windows restarted the VM: starting it again.");
                case FAULTED -> listener.step("The VM's processor stopped on a fault: starting it again.");
                case SCREEN_LOST -> listener.step("Connecting to the VM's screen again.");
            }
            if (starts >= MAX_STARTS) {
                throw new IOException("The VM was started " + starts + " times and Windows still isn't installed."
                        + logTail(vm));
            }
        }
    }

    private static String tooLong(Duration timeout) {
        return "Windows didn't finish installing within " + timeout.toMinutes()
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
        boolean pressKey = !diskWritten(vm);
        try (Running running = start(vm.withStage(VmRecord.Stage.INSTALLING), credentials)) {
            VmRecord installing = running.vm();
            VncController screen = running.screen();
            installing.save();
            if (pressKey) {
                listener.step("Pressing the key the Windows disc waits for.");
                long keysUntil = System.nanoTime() + BOOT_KEYS.toNanos();
                while (System.nanoTime() < keysUntil) {
                    screen.keyDown(SPACE);
                    screen.keyUp(SPACE);
                    Thread.sleep(500);
                }
            }
            if (first) {
                listener.step("Windows is installing: this takes 20 to 40 minutes, and the VM restarts a few times.");
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
                            throw new IOException("The VM stopped while Windows was installing." + logTail(installing));
                        }
                        awaitExit(installing, running.qemu());
                        // Unknown when an earlier run started it: a restart is the likely end.
                        int exit = running.qemu().map(Process::exitValue).orElse(0);
                        if (exit != 0) {
                            throw new IOException("QEMU ended (exit code " + exit + ") while Windows was installing."
                                    + logTail(installing));
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
                        listener.step("Windows is installed and signed in.");
                        return new Attempt(ready, Ended.READY);
                    }
                }
                Thread.sleep(FRAME_EVERY.toMillis());
            }
            throw new IOException(tooLong(timeout));
        }
    }

    /** Whether Setup has written to {@code vm}'s disk: a new one, of either kind, holds well under this. */
    static boolean diskWritten(VmRecord vm) throws IOException {
        return Files.exists(vm.disk()) && Files.size(vm.disk()) > 64L * 1024 * 1024;
    }

    /**
     * Ejects and deletes the answer disc of a VM whose Windows is installed: it holds the guest's password in plain
     * text, and no later start attaches it. Never throws: Windows is installed whatever happens here, and
     * {@link #start} tries again when the VM is next started.
     */
    private static void removeAnswerDisc(VmRecord ready) {
        try {
            ejectDiscs(ready);
        } catch (IOException e) {
            // QEMU holds the file until it ends; start() deletes it then.
        }
        try {
            Files.deleteIfExists(ready.answerIso());
        } catch (IOException e) {
            // as above
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
                    if (vm.stage() == VmRecord.Stage.READY) Files.deleteIfExists(vm.answerIso()); // left by install()
                    Path log = vm.folder().resolve(QEMU_LOG);
                    // Appended, one header per start: an install starts QEMU several times, and why the earlier
                    // ones ended is the evidence.
                    Files.writeString(log, "--- " + java.time.LocalDateTime.now().withNano(0) + " start\n",
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    started = Optional.of(new ProcessBuilder(qemu.command(vm.spec(List.of()), vm.disk(),
                            vm.folder().resolve("efivars.fd"), vm.qemuPorts()))
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
                        vm = vm.withPorts(freeVncPort(), 0, 0);
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
                Keysyms.NativeKeys.VIRTUAL_KEY, vm.name(), 30_000);
        return new Running(vm, screen, started);
    }

    /** {@code vm} with any of its QEMU ports that can't be bound now chosen again, and saved when one moved. */
    private static VmRecord withFreePorts(VmRecord vm) throws IOException {
        int vnc = bindable(vm.vncPort()) ? vm.vncPort() : freeVncPort();
        int qmp = bindable(vm.qmpPort()) && vm.qmpPort() != vnc ? vm.qmpPort() : freePort(List.of(vnc));
        int agent = bindable(vm.agentPort()) && vm.agentPort() != vnc && vm.agentPort() != qmp
                ? vm.agentPort() : freePort(List.of(vnc, qmp));
        if (vnc == vm.vncPort() && qmp == vm.qmpPort() && agent == vm.agentPort()) return vm;
        VmRecord moved = vm.withPorts(vnc, qmp, agent);
        moved.save();
        return moved;
    }

    /**
     * Runs {@code command}, a Windows command line ({@link GuestLaunch#command}), on the guest's signed-in desktop
     * and returns without waiting for it. QEMU: the guest agent writes it into the launch script and starts the
     * launch task. VMware: {@code vmrun runProgramInGuest -interactive}.
     */
    public static void runOnDesktop(VmRecord vm, VmCredentials credentials, String command)
            throws IOException, InterruptedException {
        switch (vm.hypervisor()) {
            case QEMU -> {
                try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), LAUNCH_TIMEOUT_MS)) {
                    agent.writeFile(GuestUnattend.LAUNCH_SCRIPT, GuestLaunch.script(command));
                    agent.runLaunchTask();
                }
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find()
                        .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
                Commands.require(ws.runInGuest(vm.vmx(), GUEST_USER, credentials.guest(), "C:\\Windows\\System32\\cmd.exe",
                        "/c " + command), "VMware couldn't start the game in the VM");
            }
            case UNKNOWN -> throw new IOException("This VM's hypervisor is unknown.");
        }
    }

    /** Whether the guest tools answer and the answer file's last command has run. */
    public static boolean guestReady(VmRecord vm, VmCredentials credentials) throws IOException, InterruptedException {
        return switch (vm.hypervisor()) {
            case QEMU -> {
                try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
                    yield agent.fileExists(GuestUnattend.READY_FILE);
                } catch (IOException e) {
                    yield false;
                }
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find().orElse(null);
                yield ws != null && ws.toolsRunning(vm.vmx())
                        && ws.fileExistsInGuest(vm.vmx(), GUEST_USER, credentials.guest(), GuestUnattend.READY_FILE);
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

    private static int freePort(List<Integer> alsoTaken) throws IOException {
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
