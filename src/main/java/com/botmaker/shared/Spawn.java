package com.botmaker.shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The two safe ways to start a child process, because the unsafe one is invisible.
 *
 * <p>A child writes into a pipe whose buffer is ~64 KB on Linux and as little as 4 KB on Windows. If nobody
 * reads that pipe the child blocks in {@code write()} — it does not crash, does not exit and reports nothing —
 * and a parent in an untimed {@code waitFor()} blocks with it, permanently. The symptom surfaces layers away
 * as "the game froze on startup", which is why five sites in this module had the defect (B7) while two others,
 * written later, had the cure.
 *
 * <p>So: either you want the output, in which case {@link #run} drains it and bounds the wait, or you do not,
 * in which case {@link #detached} sends both streams to {@link ProcessBuilder.Redirect#DISCARD} and never waits
 * at all. {@code DISCARD} rather than {@code inheritIO()} on purpose — inheriting would spill a game's chatter
 * into the bot's own stdout, where it reads as the bot's.
 */
public final class Spawn {

    /** How long to let the drain finish after the child is already gone; EOF is imminent by then. */
    private static final long DRAIN_GRACE_MS = 2_000;

    private Spawn() {}

    /** A finished child: its exit status and everything it wrote (stdout and stderr merged, in order). */
    public record Completed(int exitCode, String output) {

        /** Whether the child exited 0 — the only exit status most callers here care to distinguish. */
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Starts a child nobody will read from: both streams discarded, no wait. Use for fire-and-forget launches
     * (a game, a store launcher, an emulator) where the handle — not the output — is the point.
     */
    public static Process detached(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** @see #detached(List) */
    public static Process detached(String... command) throws IOException {
        return detached(List.of(command));
    }

    /**
     * Runs a child to completion, reading it while it runs, and gives up after {@code timeout}.
     *
     * <p>The pipe is drained continuously while the child runs, on a thread of its own — the child must never
     * be able to fill the buffer, and the reader must never be what the timeout is waiting for. Streams are
     * merged so there is one pipe to drain; a second, unread stderr is the same bug with a smaller buffer,
     * which is how the {@code tasklist} and {@code pgrep} probes carried it while looking drained.
     *
     * @return what the child wrote and its status, or {@code null} if it outlived {@code timeout} (in which
     *         case it has been killed — a probe that hangs must not become a caller that hangs)
     */
    public static Completed run(Duration timeout, List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        // The drain has to run off this thread. Reading to EOF here would be correct for a child that exits and
        // useless for one that doesn't: EOF arrives only when the pipe closes, so the read outlasts the timeout
        // it is supposed to be bounded by, and the caller waits for the child anyway — the hang, relocated.
        // Read on the drain thread, publish once through the AtomicReference: the reader and this thread never
        // touch the same buffer, so there is nothing to synchronize beyond that single handoff.
        AtomicReference<String> sink = new AtomicReference<>("");
        Thread drain = new Thread(() -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (var in = p.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException closedUnderUs) {
                // destroyForcibly() below closes the pipe mid-read; whatever was read is what we report.
            } finally {
                sink.set(buffer.toString(StandardCharsets.UTF_8));
            }
        }, "spawn-drain");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            drain.join(DRAIN_GRACE_MS); // the child is gone; EOF is imminent, not hypothetical
            return new Completed(p.exitValue(), sink.get());
        } finally {
            if (p.isAlive()) {
                // The whole tree, and collected *before* the parent dies: killing it reparents its children to
                // init, where descendants() can no longer reach them. A shell that could not exec-optimize the
                // command (dash forks where bash execs; any pipeline forks everywhere) would otherwise leave the
                // real worker running — the leak this timeout exists to prevent, traded for the hang.
                List<ProcessHandle> tree = p.descendants().toList();
                p.destroyForcibly();
                tree.forEach(ProcessHandle::destroyForcibly);
            }
        }
    }

    /** @see #run(Duration, List) */
    public static Completed run(Duration timeout, String... command) throws IOException, InterruptedException {
        return run(timeout, List.of(command));
    }
}
