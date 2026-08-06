package com.botmaker.shared.emulator;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.platform.Os;

import java.io.File;
import java.time.Duration;
import java.util.List;

/**
 * The one place that shells out to {@code waydroid} (and the couple of host probes around it). Discovery,
 * resolution and diagnostics all need the same "run this, give me the text, never throw" call, and three
 * private copies of it would be three different timeouts.
 *
 * <p>Everything here is <b>read-only or session-scoped</b>: nothing in this class runs a privileged command.
 * The remedies that need {@code sudo} are strings in {@link WaydroidDiagnostics}, shown to the user and never
 * executed — see that class for why.
 */
final class WaydroidCli {

    /** The binary every method here goes through; also the installed-ness probe. */
    static final String WAYDROID = "waydroid";

    /**
     * {@code waydroid} talks to a container over a socket and answers in well under a second when it is
     * healthy. The interesting case is when it is <em>not</em>: a probe that blocks would hang the whole
     * platform scan, so it is bounded like every other discovery probe.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private WaydroidCli() {}

    /**
     * Whether {@code binary} is an executable on {@code PATH}. This is the install probe: Waydroid has no
     * registry key and no canonical install directory (distro packages, {@code /usr/bin} vs {@code /usr/local}),
     * so "is the command there" is both the most portable test and exactly the condition under which anything
     * else here can work.
     */
    static boolean onPath(String binary) {
        String path = System.getenv("PATH");
        if (binary == null || path == null || path.isBlank()) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, binary);
            if (candidate.isFile() && candidate.canExecute()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the {@code waydroid} command is usable on this machine. */
    static boolean available() {
        // Waydroid is a Linux kernel container and exists nowhere else.
        return Os.current().isLinux() && onPath(WAYDROID);
    }

    /**
     * Runs {@code waydroid <args>} and returns its merged output, trimmed — or {@code null} if it could not
     * run, timed out, or exited non-zero. Callers treat {@code null} as "no answer", never as an error to
     * propagate: a scan must survive a half-installed Waydroid.
     */
    static String waydroid(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = WAYDROID;
        System.arraycopy(args, 0, command, 1, args.length);
        return run(command);
    }

    /** As {@link #waydroid}, for any command. {@code null} on failure, timeout or a non-zero exit. */
    static String run(String... command) {
        try {
            Spawn.Completed done = Spawn.run(TIMEOUT, List.of(command));
            return done == null || !done.ok() ? null : done.output().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * As {@link #run}, but keeps the output of a non-zero exit too. {@code systemctl is-active} answers
     * "inactive" <em>and</em> exits 3, so the useful word would be thrown away by the stricter form.
     */
    static String runAnyExit(String... command) {
        try {
            Spawn.Completed done = Spawn.run(TIMEOUT, List.of(command));
            return done == null ? null : done.output().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
