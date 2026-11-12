package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Starts and stops emulator instances by running the host console command each {@link EmulatorPlatform}
 * resolved at discovery time (e.g. {@code ldconsole.exe launch --index 0},
 * {@code MuMuManager.exe control -v 0 launch}). The transport underneath ({@link AdbDevice}) can only talk to
 * an <em>already-running</em> instance; this is the missing verb that brings one up (or takes it down) from
 * outside the guest.
 *
 * <p>Best-effort and never-throws, mirroring discovery: a blank command (product has no console tool, or it
 * couldn't be located) or a spawn failure returns {@code false} rather than raising. The spawn is
 * fire-and-forget — these console tools return immediately while the emulator boots in the background, so a
 * {@code true} means "the launch was dispatched", not "the instance is up". Callers that need readiness poll
 * the ADB port afterwards ({@link EmulatorReadiness#bringUp}, which also notices a start whose process never ran).
 *
 * <p>On Windows a program runs from its own folder, as Explorer would start it. A program Windows refuses to start
 * without its permission prompt (error 740: MSI App Player's player) is started again through ShellExecute
 * ({@link WindowsCommands#shellExecute}).
 */
public final class EmulatorLauncher {

    private EmulatorLauncher() {}

    /** Dispatches {@code instance}'s host launch command. {@code false} if it has none or the spawn fails. */
    public static boolean launch(EmulatorInstance instance) {
        if (instance == null) return false;
        if (HeldInstances.heldElsewhere(instance)) {
            Diag.log("[Emulator] " + HeldInstances.why(instance));
            return false;
        }
        // An AVD is reached through the adb server, which nothing else may have started on this login.
        if (instance.platformId() == PlatformId.AVD && instance.canLaunch()) AdbTools.startServer();
        return run(instance.launchCommand());
    }

    /** Dispatches {@code instance}'s host stop command. {@code false} if it has none or the spawn fails. */
    public static boolean stop(EmulatorInstance instance) {
        return instance != null && run(instance.stopCommand());
    }

    /**
     * Stops {@code instance} and waits until its product says it is stopped. {@code false} when it has no stop
     * command, the command did not run, or it is still up after {@code timeout}.
     */
    public static boolean stopAndWait(EmulatorInstance instance, Duration timeout) throws InterruptedException {
        if (instance == null || !instance.canStop() || !stop(instance)) return false;
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            Thread.sleep(1000);
            if (EmulatorLiveness.check(instance).state() == EmulatorState.STOPPED) return true;
        }
        return false;
    }

    /**
     * Starts a product's own program detached (BlueStacks' instance manager), through Windows' permission prompt when
     * it asks for administrator rights, as a launch does. {@code false} when it did not start.
     */
    public static boolean open(List<String> command) {
        return run(command);
    }

    private static boolean run(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        try {
            start(command);
            return true;
        } catch (IOException e) {
            if (!needsElevation(e)) {
                Diag.log("[Emulator] couldn't run " + command.getFirst() + ": " + e.getMessage());
                return false;
            }
            Diag.log("[Emulator] " + command.getFirst() + " asks for administrator rights: starting it through Windows");
            try {
                start(WindowsCommands.shellExecute(command));
                return true;
            } catch (IOException again) {
                Diag.log("[Emulator] couldn't run " + command.getFirst() + " through Windows: " + again.getMessage());
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void start(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        File folder = folderOf(command.getFirst());
        if (folder != null) builder.directory(folder);
        builder.start();
    }

    /** The folder of {@code program} when it is a path to a file there, else {@code null} (a bare name on PATH). */
    static File folderOf(String program) {
        try {
            Path path = Path.of(program);
            Path parent = path.getParent();
            return path.isAbsolute() && parent != null && Files.isDirectory(parent) ? parent.toFile() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether Windows refused the start for want of elevation: {@code CreateProcess error=740}. */
    static boolean needsElevation(IOException e) {
        return e.getMessage() != null && e.getMessage().contains("error=740");
    }
}
