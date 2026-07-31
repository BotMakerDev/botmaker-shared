package com.botmaker.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two shapes B7's five sites were converted to, tested where they now live rather than five times over.
 *
 * <p>{@link ProcessSpawnStreamTest} in {@code launch/} keeps the end-to-end proof through {@code GameLauncher};
 * this covers the helper's own promises, including the one no caller can exercise from outside — that a child
 * which never finishes is killed and reported as a timeout instead of becoming the caller's hang.
 */
@DisabledOnOs(OS.WINDOWS) // POSIX shell for the chatty/hanging children; Spawn itself is platform-neutral
class SpawnTest {

    /** Well past a 64 KB pipe buffer, instant when someone is reading. */
    private static final int CHATTY_BYTES = 256 * 1024;

    private static final String SH = "/bin/sh";

    private static String floodBothPipes() {
        return "yes botmaker | head -c " + CHATTY_BYTES
                + "; yes botmaker | head -c " + CHATTY_BYTES + " 1>&2";
    }

    @Test
    void detachedSurvivesAChildThatFloodsBothPipes() throws Exception {
        Process p = Spawn.detached(SH, "-c", floodBothPipes());
        assertTrue(p.waitFor(15, TimeUnit.SECONDS),
                "a discarded-output child still wedged — DISCARD is not being applied to both streams");
        assertEquals(0, p.exitValue());
    }

    @Test
    void runReturnsEverythingTheChildWroteToEitherStream() throws Exception {
        Spawn.Completed done = Spawn.run(Duration.ofSeconds(15), SH, "-c", floodBothPipes());
        assertNotNull(done, "a child that writes 512 KB and exits was reported as a timeout");
        assertTrue(done.ok(), "exit " + done.exitCode());
        assertTrue(done.output().length() >= 2 * CHATTY_BYTES,
                "read " + done.output().length() + " bytes of " + (2 * CHATTY_BYTES)
                        + " — stderr is not merged, which is the same bug with a smaller buffer");
    }

    @Test
    void runReportsANonZeroExit() throws Exception {
        Spawn.Completed done = Spawn.run(Duration.ofSeconds(10), SH, "-c", "echo nope 1>&2; exit 3");
        assertNotNull(done);
        assertEquals(3, done.exitCode());
        assertFalse(done.ok());
        assertTrue(done.output().contains("nope"), "stderr was dropped: " + done.output());
    }

    /**
     * The reason the timeout exists: a probe that never returns must cost a wrong answer, not a stuck caller.
     * The child is killed on the way out — a timeout that leaked the process would trade a hang for a leak.
     */
    @Test
    void aChildThatOutlivesTheTimeoutIsKilledAndReportedAsNull() throws Exception {
        long start = System.nanoTime();
        Spawn.Completed done = Spawn.run(Duration.ofMillis(300), SH, "-c", "sleep 120");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNull(done, "a child still running after its timeout was reported as completed");
        assertTrue(elapsedMs < 15_000, "run() waited " + elapsedMs + " ms for a 300 ms timeout");
        // The sleep would have held the pipe open for two minutes, so a drain done on the calling thread would
        // have blocked right past the timeout — this elapsed time is the whole point of the drain thread.
        assertTrue(elapsedMs < 5_000, "run() took " + elapsedMs + " ms — the drain, not the child, was the wait");
        assertFalse(stillRunning("sleep 120"), "the timed-out child was left running");
    }

    /** SIGKILL is delivered asynchronously, so give the reap a moment before calling a survivor a leak. */
    private static boolean stillRunning(String commandFragment) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean alive = ProcessHandle.allProcesses()
                    .anyMatch(h -> h.info().commandLine().filter(c -> c.contains(commandFragment)).isPresent());
            if (!alive) {
                return false;
            }
            Thread.sleep(50);
        }
        return true;
    }
}
