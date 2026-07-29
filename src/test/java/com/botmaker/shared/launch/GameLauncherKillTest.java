package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kill has to hit the host's copy of a game and nothing else. It used to be {@code pkill -f <token>}, a raw
 * substring match over every command line on the machine, which killed two things it must never touch — the
 * user's launcher UI (its argv carries the whole library, so every app id "matches") and <em>this JVM</em> when
 * the target appeared in its own arguments. The second one is not hypothetical: a live isolated bring-up died
 * at exactly this call, having signalled the process performing it.
 *
 * <p>Both halves are asserted with real processes, because the bug was entirely about which real processes get
 * signalled: a child of ours must survive, and an unrelated process must not.
 */
@EnabledOnOs(OS.LINUX)
class GameLauncherKillTest {

    /**
     * A process that stays alive <em>and keeps the token in its own argv</em>. The obvious {@code sleep 120 #
     * token} does not work: the shell execs {@code sleep}, replacing its command line, and the token vanishes
     * along with the thing under test. A shell that never execs — it loops — keeps its argv, and the token is
     * passed as {@code $0}.
     */
    private static List<String> spinner(String token) {
        return List.of("sh", "-c", "while :; do sleep 1; done", token);
    }

    @Test
    void ourOwnChildIsNeverKilledByName() throws Exception {
        String token = "botmaker-kill-self-" + UUID.randomUUID();
        Process ours = new ProcessBuilder(spinner(token)).start();
        try {
            // It is running and it does match the token — so the only thing that can spare it is the
            // own-process-tree exclusion, which is exactly what is under test.
            assertTrue(waitFor(() -> RunningProbe.commandLineMentions(token)),
                    "the child should be visible to the probe");

            GameLauncher.kill(token);

            assertFalse(ours.waitFor(2, TimeUnit.SECONDS), "our own child must survive a kill by name");
            assertTrue(ours.isAlive());
        } finally {
            ours.destroyForcibly();
        }
    }

    @Test
    void anUnrelatedProcessCarryingTheTokenIsKilled() throws Exception {
        String token = "botmaker-kill-host-" + UUID.randomUUID();
        // --fork is load-bearing: plain setsid only forks when it is already a process-group leader, and a
        // JVM child never is, so it would exec in place and stay our direct child — which the kill (correctly)
        // spares, and the test would be asserting the opposite of what it claims. With --fork the process is
        // reparented away, standing in for the host's own copy of a game.
        List<String> detached = new java.util.ArrayList<>(List.of("setsid", "--fork"));
        detached.addAll(spinner(token));
        Process spawner = new ProcessBuilder(detached).start();
        spawner.waitFor(5, TimeUnit.SECONDS);
        try {
            assertTrue(waitFor(() -> RunningProbe.commandLineMentions(token)),
                    "the detached process should be visible to the probe");

            GameLauncher.kill(token);

            assertTrue(waitFor(() -> !RunningProbe.commandLineMentions(token)),
                    "a process outside our tree must actually be killed");
        } finally {
            RunningProbe.processesRunning(token).forEach(ProcessHandle::destroyForcibly);
        }
    }

    /** Poll for up to ~3s: process creation and signal delivery are both asynchronous. */
    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
