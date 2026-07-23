package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A launcher that merely <em>knows about</em> a game must not count as running it.
 *
 * <p>The reported bug: a {@code heroic:} target refused to launch because it was already detected as running,
 * and started working the moment the Heroic launcher itself was quit. Heroic's own UI process carries every
 * {@code AppName} in its library, so the command-line layer matched it, every caller skipped the cold launch,
 * and nothing ever started.
 *
 * <p>These tests stand up real processes under the names in question rather than mocking the process table —
 * the deny-list is entirely about what the OS reports, so a fake would prove nothing. Each script blocks on
 * {@code sleep} so it is alive for the duration of the scan.
 */
@DisabledOnOs(OS.WINDOWS) // The scripts are shell; the deny-list itself is OS-independent.
class RunningProbeLauncherTest {

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
    void aLauncherUiCarryingTheAppNameIsNotEvidenceTheGameIsRunning(@TempDir Path dir) throws Exception {
        String appName = "Firestone" + UUID.randomUUID();
        Process heroic = runNamed(dir, "heroic", "--library", appName);
        try {
            assertFalse(RunningProbe.commandLineMentions(appName),
                    "Heroic merely being open must not report the game as running — that is the reported bug");
        } finally {
            heroic.destroyForcibly();
        }
    }

    @Test
    void theWrapperTheGameActuallyRunsUnderStillCounts(@TempDir Path dir) throws Exception {
        // Matching wrappers is the whole point of this layer: a Heroic/Epic game really does run under one.
        String appName = "Firestone" + UUID.randomUUID();
        Process wrapper = runNamed(dir, "umu-run", appName + ".exe");
        try {
            assertTrue(RunningProbe.commandLineMentions(appName),
                    "a wrapper process carrying the token is exactly what this layer exists to find");
        } finally {
            wrapper.destroyForcibly();
        }
    }

    @Test
    void legendaryCountsOnlyWhenItIsActuallyLaunching(@TempDir Path dir) throws Exception {
        // Heroic spawns legendary for library work too, so the bare binary name is not enough evidence.
        String appName = "Firestone" + UUID.randomUUID();
        Process listing = runNamed(dir, "legendary", "list-installed", appName);
        try {
            assertFalse(RunningProbe.commandLineMentions(appName),
                    "legendary doing library work is not a running game");
        } finally {
            listing.destroyForcibly();
        }

        Process launching = runNamed(dir, "legendary", "launch", appName);
        try {
            assertTrue(RunningProbe.commandLineMentions(appName),
                    "legendary with the launch verb is running the game");
        } finally {
            launching.destroyForcibly();
        }
    }

    @Test
    void everyWrapperAGameRunsUnderStaysVisible(@TempDir Path dir) throws Exception {
        // The opposite failure mode, and the worse one: exclude a wrapper by mistake and a running game reads
        // as not running, so the bot relaunches it on every pass. Steam's own chain is reaper → proton.
        for (String wrapper : Set.of("reaper", "proton", "wine", "gogdl")) {
            String token = "AppId=" + Math.abs(UUID.randomUUID().hashCode());
            Process p = runNamed(dir, wrapper, "SteamLaunch", token, "--");
            try {
                assertTrue(RunningProbe.commandLineMentions(token),
                        wrapper + " is how a launcher-started game runs; it must still count");
            } finally {
                p.destroyForcibly();
            }
        }
    }
}
