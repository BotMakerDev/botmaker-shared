package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal that saves a two-minute wait and a coredump: an isolated launch of a store kind cannot work while
 * that launcher's own UI is open, because the second invocation is forwarded to it and the game starts on
 * {@code :0}.
 *
 * <p>Like {@link RunningProbeLauncherTest}, these stand up real processes under the names in question rather
 * than mocking the process table — the probe is entirely about what the OS reports, so a fake proves nothing.
 */
@DisabledOnOs(OS.WINDOWS) // The scripts are shell; the probe itself is OS-independent.
class HostLauncherProbeTest {

    /** A live process named {@code name} whose argv carries {@code args}. Killed by the caller. */
    private static Process runNamed(Path dir, String name, String... args) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\nsleep 30\n");
        script.toFile().setExecutable(true);
        String[] command = new String[args.length + 1];
        command[0] = script.toString();
        System.arraycopy(args, 0, command, 1, args.length);
        return new ProcessBuilder(command).start();
    }

    @Test
    void anOpenLauncherUiIsDetected(@TempDir Path dir) throws Exception {
        Process heroic = runNamed(dir, "heroic", "--no-gui");
        try {
            assertTrue(HostLauncherProbe.isRunning(LaunchKind.HEROIC),
                    "an open Heroic must be seen — it is what would swallow the isolated launch");
            assertFalse(HostLauncherProbe.isRunning(LaunchKind.FAUGUS),
                    "one launcher being open says nothing about another");
        } finally {
            heroic.destroyForcibly();
        }
    }

    @Test
    void theFlatpakFormIsDetectedThroughItsApplicationId(@TempDir Path dir) throws Exception {
        // The install shape most users have: no `heroic` on PATH at all, only `flatpak run <app-id>`.
        Process flatpak = runNamed(dir, "flatpak", "run", "com.heroicgameslauncher.hgl");
        try {
            assertTrue(HostLauncherProbe.isRunning(LaunchKind.HEROIC),
                    "a Flatpak-installed launcher is still a launcher on :0");
        } finally {
            flatpak.destroyForcibly();
        }
    }

    @Test
    void kindsThatDontRouteThroughADaemonAreNeverRefused() {
        // exe:/cli: are our own child, and an emulator app is driven over ADB — no single-instance handoff.
        assertFalse(HostLauncherProbe.routesThroughDaemon(LaunchKind.EXE));
        assertFalse(HostLauncherProbe.routesThroughDaemon(LaunchKind.CLI));
        assertFalse(HostLauncherProbe.routesThroughDaemon(LaunchKind.EMULATOR_APP));
        assertFalse(HostLauncherProbe.routesThroughDaemon(null));
        assertFalse(HostLauncherProbe.isRunning(LaunchKind.EXE));
        assertFalse(HostLauncherProbe.isRunning((LaunchSpec) null));
        assertTrue(HostLauncherProbe.routesThroughDaemon(LaunchKind.HEROIC));
    }

    @Test
    void theRefusalNamesTheProductAndWhatToDo() {
        String message = HostLauncherProbe.refusalMessage(LaunchKind.HEROIC);
        assertTrue(message.contains("Heroic"), message);
        assertTrue(message.contains("Close Heroic"), message);
    }
}
