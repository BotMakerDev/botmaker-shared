package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.vnc.Keysyms;
import com.botmaker.shared.vnc.VncController;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A game VM on an Apple Silicon Mac, run by {@link Tart}: what {@link VmSetup} does for QEMU and VMware, for a VM
 * that is cloned rather than installed. {@link VmSetup}'s public entry points hand a {@link Hypervisor#TART} VM here.
 * <ul>
 *   <li><b>Setup</b> downloads Tart, clones Cirrus Labs' macOS image (about 25 GB, shared by every VM made from
 *       it), sizes the clone, and boots it once until its guest agent answers.</li>
 *   <li><b>A start</b> runs {@code tart run --no-graphics --vnc-experimental}: the Virtualization framework's own
 *       VNC server, on the host, which delivers input through the VM's virtual keyboard and pointer as QEMU's does.
 *       Its address and its password are new each run and printed by Tart, so its output goes to
 *       {@value #LOG} in the VM's folder (readable by the user only), where a second process finds them.</li>
 *   <li><b>The guest</b> is reached through {@code tart exec}, Tart's guest agent: no network, no password.</li>
 * </ul>
 */
final class TartSetup {

    /** Tart's output for each run of the VM, its VNC address among it. */
    static final String LOG = "tart.log";

    private static final Duration SET = Duration.ofMinutes(5);
    private static final Duration START = Duration.ofSeconds(60);
    private static final Duration EXEC = Duration.ofSeconds(30);
    private static final Duration GAME_CHECK = Duration.ofMinutes(1);
    private static final Duration SHUT_DOWN = Duration.ofMinutes(3);
    private static final Duration READY_POLL = Duration.ofSeconds(5);
    private static final long FIRST_FRAME_MS = 30_000;

    private TartSetup() {}

    /** What stops setting up a {@code guestOs} Tart VM here; empty when nothing. */
    static List<String> problems(GuestOs guestOs) {
        List<String> problems = new ArrayList<>();
        if (!Tart.supported()) problems.add("A game VM on a Mac needs Apple Silicon and macOS 14 or later.");
        if (guestOs != GuestOs.MACOS) problems.add("A game VM on a Mac runs macOS for now.");
        return problems;
    }

    /** Downloads Tart if it isn't here, and records the VM as {@link VmRecord.Stage#PREPARED}. */
    static VmRecord prepare(Path folder, String name, GuestOs guestOs, VmSize size, VmSetup.Listener listener)
            throws IOException, InterruptedException {
        listener.step("Downloading Tart " + Tart.VERSION + " (23 MB), which runs the VM.");
        Tart.install((bytes, total) -> { });
        String image = Tart.image(guestOs, System.getProperty("os.version", ""));
        VmRecord vm = new VmRecord(folder, name, Hypervisor.TART, VmRecord.Stage.PREPARED, size, Path.of(""), "en-US",
                0, 0, 0, 0, guestOs, image);
        // Kept for what reads every VM's passwords; Tart's guest signs in by itself, and its VNC password is per run.
        VmCredentials.random().save(folder);
        vm.save();
        return vm;
    }

    /**
     * Clones the image (once; a setup that stopped goes on), sizes the clone, starts it and waits until its guest
     * agent answers, following its screen. Records {@link VmRecord.Stage#READY}.
     */
    static VmRecord install(VmRecord vm, VmSetup.Listener listener, Duration timeout)
            throws IOException, InterruptedException {
        Tart tart = Tart.install((bytes, total) -> { });
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!exists(tart, vm.name())) {
            listener.step("Downloading macOS for the VM (about 25 GB, once for every VM made from it)…");
            clone(tart, vm, listener, deadline);
        }
        if (vm.stage() == VmRecord.Stage.PREPARED) {
            VmSize size = vm.size();
            Commands.require(tart.run(SET, "set", vm.name(), "--cpu", Integer.toString(size.cpus()),
                    "--memory", Integer.toString(size.memoryMb()), "--disk-size", Integer.toString(size.diskGb())),
                    "Tart couldn't size the VM");
            vm = vm.withStage(VmRecord.Stage.INSTALLING);
            vm.save();
        }
        listener.step("Starting macOS in the VM for the first time…");
        try (VmSetup.Running running = start(vm)) {
            while (!guestReady(vm)) {
                if (System.nanoTime() > deadline) throw new IOException("macOS in the VM didn't answer in time.");
                BufferedImage frame = running.screen().captureScreen();
                if (frame != null) listener.frame(frame);
                Thread.sleep(READY_POLL.toMillis());
            }
        }
        VmRecord ready = vm.withStage(VmRecord.Stage.READY);
        ready.save();
        return ready;
    }

    /** Whether Tart has a VM named {@code name}. */
    private static boolean exists(Tart tart, String name) throws IOException, InterruptedException {
        Spawn.Completed list = tart.run(Duration.ofSeconds(30), "list", "--source", "local", "--quiet");
        return list.ok() && list.output().lines().map(String::strip).anyMatch(name::equals);
    }

    /** {@code tart clone <image> <name>}, saying each line of its progress. */
    private static void clone(Tart tart, VmRecord vm, VmSetup.Listener listener, long deadline)
            throws IOException, InterruptedException {
        Process clone = new ProcessBuilder(tart.command("clone", vm.image(), vm.name()))
                .redirectErrorStream(true).start();
        clone.getOutputStream().close();
        StringBuilder said = new StringBuilder();
        Thread reader = Thread.ofPlatform().daemon().name("tart-clone").start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(clone.getInputStream(),
                    StandardCharsets.UTF_8))) {
                StringBuilder line = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    // Progress redraws its line with a carriage return.
                    if (c == '\r' || c == '\n') {
                        String text = line.toString().strip();
                        if (!text.isEmpty()) {
                            listener.step("Downloading macOS for the VM: " + text);
                            synchronized (said) {
                                said.setLength(0);
                                said.append(text);
                            }
                        }
                        line.setLength(0);
                    } else {
                        line.append((char) c);
                    }
                }
            } catch (IOException ended) {
                // the clone ended
            }
        });
        try {
            while (clone.isAlive()) {
                if (System.nanoTime() > deadline) throw new IOException("The macOS download didn't finish in time.");
                Thread.sleep(1_000);
            }
        } finally {
            if (clone.isAlive()) clone.destroy();
        }
        reader.join(2_000);
        if (clone.exitValue() != 0) {
            synchronized (said) {
                throw new IOException("Tart couldn't download macOS: " + said);
            }
        }
    }

    /**
     * Starts {@code vm} without a window if it isn't running, and connects to its screen through the VNC address its
     * run printed.
     */
    static VmSetup.Running start(VmRecord vm) throws IOException, InterruptedException {
        Tart tart = Tart.find().orElseThrow(() -> new IOException("Tart isn't here; set the VM up again."));
        Path log = vm.folder().resolve(LOG);
        Optional<Process> started = Optional.empty();
        Optional<Tart.Vnc> vnc;
        if (tart.running(vm.name())) {
            vnc = Tart.vnc(read(log));
            if (vnc.isEmpty()) throw new IOException("The VM " + vm.name() + " runs, but not from BotMaker: shut it down "
                    + "and start it again.");
        } else {
            long from = startLog(log);
            Process run = new ProcessBuilder(tart.command("run", vm.name(), "--no-graphics", "--vnc-experimental"))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectErrorStream(true)
                    .start();
            run.getOutputStream().close();
            started = Optional.of(run);
            long until = System.nanoTime() + START.toNanos();
            while ((vnc = Tart.vnc(since(log, from))).isEmpty()) {
                if (System.nanoTime() > until || !run.isAlive()) {
                    run.destroy();
                    throw new IOException("Tart didn't start the VM: " + tail(since(log, from)));
                }
                Thread.sleep(500);
            }
        }
        Tart.Vnc at = vnc.get();
        try {
            VncController screen = VncController.connect(at.host(), at.port(), at.password(),
                    Keysyms.NativeKeys.KEYSYM, vm.name(), List::of, FIRST_FRAME_MS);
            return new VmSetup.Running(vm, screen, started);
        } catch (IOException e) {
            // A run nobody can see would be found running next time, its address perhaps gone stale: stop it.
            started.ifPresent(Process::destroy);
            throw e;
        }
    }

    /**
     * Starts {@value #LOG} afresh for a new run, readable by the user only, and returns where the run's output
     * begins: only the running VM's address is ever read from it.
     */
    private static long startLog(Path log) throws IOException {
        Files.deleteIfExists(log);
        Files.createFile(log, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        String header = "--- " + java.time.LocalDateTime.now().withNano(0) + " start\n";
        Files.writeString(log, header, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return header.length();
    }

    /** What this run wrote to {@code log}: from {@code from}, where {@link #startLog} left it. */
    private static String since(Path log, long from) {
        String all = read(log);
        return all.substring((int) Math.min(from, all.length()));
    }

    private static String read(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The last lines Tart said, none of them its VNC address. */
    private static String tail(String said) {
        List<String> lines = said.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.contains(Tart.VNC_LINE))
                .toList();
        return lines.isEmpty() ? "it said nothing." : String.join(" ", lines.subList(Math.max(0, lines.size() - 3),
                lines.size()));
    }

    /** Whether Tart runs {@code vm}. */
    static boolean running(VmRecord vm) {
        return Tart.find().map(t -> t.running(vm.name())).orElse(false);
    }

    /**
     * Asks macOS to shut down and waits up to {@link #SHUT_DOWN}, after which Tart stops the VM.
     *
     * @return whether macOS shut down by itself; {@code false} when Tart had to stop it at the timeout
     */
    static boolean shutDown(VmRecord vm) throws IOException, InterruptedException {
        if (!running(vm)) return true;
        Tart tart = Tart.find().orElseThrow(() -> new IOException("Tart isn't here."));
        long began = System.nanoTime();
        Spawn.Completed stopped = tart.run(SHUT_DOWN.plusSeconds(30), "stop", vm.name(), "--timeout",
                Long.toString(SHUT_DOWN.toSeconds()));
        Commands.require(stopped, "Tart couldn't stop the VM");
        // Tart says nothing of how it stopped: one that took the whole timeout was forced.
        return System.nanoTime() - began < SHUT_DOWN.toNanos();
    }

    /** Runs {@code script} with {@code sh} in the guest, through Tart's guest agent, and waits up to {@code timeout}. */
    private static Spawn.Completed exec(VmRecord vm, String script, Duration timeout)
            throws IOException, InterruptedException {
        Tart tart = Tart.find().orElseThrow(() -> new IOException("Tart isn't here."));
        return tart.run(timeout, "exec", vm.name(), "/bin/sh", "-c", script);
    }

    /** Whether the guest agent answers, which it does once macOS has started and signed in. */
    static boolean guestReady(VmRecord vm) throws InterruptedException {
        try {
            return exec(vm, "true", Duration.ofSeconds(10)).ok();
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs {@code command}, a shell command ({@link GuestLaunch#macCommand}), on the guest's signed-in desktop. */
    static void runOnDesktop(VmRecord vm, String command) throws IOException, InterruptedException {
        Spawn.Completed ran = exec(vm, asDesktopUser(command), EXEC);
        if (!ran.ok()) throw new IOException("The VM couldn't start it (exit code " + ran.exitCode() + "): "
                + ran.output().strip());
    }

    /**
     * {@code command} run as the signed-in user, in their desktop session: the guest agent may run as root, and
     * {@code open} from outside the desktop's session starts nothing on it. Package-private and pure for the tests.
     */
    static String asDesktopUser(String command) {
        String user = Tart.GUEST_USER;
        return "if [ \"$(id -u)\" = 0 ]; then exec launchctl asuser \"$(id -u " + user + ")\" sudo -u " + user
                + " /bin/sh -c " + GuestLaunch.shellQuoted(command) + "; else exec /bin/sh -c "
                + GuestLaunch.shellQuoted(command) + "; fi";
    }

    /** Whether {@code spec} runs in the guest, and with {@code stop}, ends it first ({@link GuestGame#macScript}). */
    static GuestGame.Found game(VmRecord vm, LaunchSpec spec, boolean stop) throws IOException, InterruptedException {
        Optional<String> script = GuestGame.macScript(spec, stop);
        if (script.isEmpty()) return GuestGame.Found.UNKNOWN;
        Spawn.Completed ran = exec(vm, script.get(), GAME_CHECK);
        String doing = (stop ? "stop " : "look for ") + spec.describe();
        if (!ran.ok()) throw new IOException("The VM couldn't " + doing + " (exit code " + ran.exitCode() + "): "
                + ran.output().strip());
        GuestGame.Found found = GuestGame.parse(ran.output());
        if (!found.survived().isEmpty()) {
            throw new IOException("The VM couldn't end " + String.join(", ", found.survived()) + ".");
        }
        return found;
    }

    /** Whether {@code launcher}'s Mac app is installed in the guest. */
    static boolean guestHas(VmRecord vm, GuestLauncher launcher) throws IOException, InterruptedException {
        Optional<String> app = launcher.macApp();
        if (app.isEmpty()) return false;
        return exec(vm, "[ -d " + GuestLaunch.shellQuoted(app.get()) + " ]", EXEC).ok();
    }
}
