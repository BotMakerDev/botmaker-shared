package com.botmaker.shared.emulator;

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
 * the ADB port afterwards.
 */
public final class EmulatorLauncher {

    private EmulatorLauncher() {}

    /** Dispatches {@code instance}'s host launch command. {@code false} if it has none or the spawn fails. */
    public static boolean launch(EmulatorInstance instance) {
        return instance != null && run(instance.launchCommand());
    }

    /** Dispatches {@code instance}'s host stop command. {@code false} if it has none or the spawn fails. */
    public static boolean stop(EmulatorInstance instance) {
        return instance != null && run(instance.stopCommand());
    }

    private static boolean run(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        try {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
