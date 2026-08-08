package com.botmaker.shared.emulator;

import dadb.AdbShellPacket;
import dadb.AdbShellStream;
import dadb.Dadb;

import java.io.IOException;

/**
 * One {@code sh} held open across many commands, instead of a fresh one per command.
 *
 * <p>{@code Dadb.shell(cmd)} opens an ADB stream, lets {@code adbd} fork a shell, runs one command and tears
 * it all down. For a bot that taps a few times a second, that per-call setup is paid over and over: a stream
 * OPEN round-trip plus a process spawn, before the command has started. Holding the shell open pays it once.
 *
 * <p><b>What this does not fix, and the honesty about it matters:</b> {@code input tap} is a shell script that
 * execs {@code app_process} — a whole JVM start, per tap, on the device. That dominates, and no amount of
 * transport work touches it. This is a real but partial win; removing the JVM start is what the scrcpy control
 * socket is for.
 *
 * <h2>Why each command still waits for a reply</h2>
 *
 * Writing a command and moving on would be faster still, and wrong twice over. A bot's tap must have landed
 * before the next frame is captured, or it is racing its own screenshot — the ordering <em>is</em> the
 * semantics. And nothing would ever read the shell's output: ADB's per-stream window stops advancing when a
 * peer stops acknowledging, so a command that printed anything would eventually wedge the shell it was
 * sharing. So each command is followed by an echoed marker, and {@link #run} reads until it sees it.
 *
 * <p>The marker is randomised per session, because a fixed one could appear in a command's own output — the
 * output of {@code pm list packages} is not something we control — and a false match would return a truncated
 * result while leaving the rest of it to corrupt the next command.
 */
final class AdbShellSession implements AutoCloseable {

    private final AdbShellStream stream;
    private final String marker;

    private AdbShellSession(AdbShellStream stream, String marker) {
        this.stream = stream;
        this.marker = marker;
    }

    /** Opens an interactive shell ({@code shell,v2,raw:} with no command) and the marker it will echo. */
    static AdbShellSession open(Dadb dadb) throws IOException {
        String marker = "__bm" + Long.toHexString(System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE))
                + "__";
        return new AdbShellSession(dadb.openShell(""), marker);
    }

    /**
     * Runs {@code command} and returns its stdout, exactly as {@code Dadb.shell(...).getOutput()} would.
     *
     * <p>Synchronised because one shell is one serial conversation: two callers interleaving writes would
     * produce two commands neither of them asked for. Bots are multi-threaded, so this is not theoretical.
     *
     * @throws Failed if the shell could not carry the command
     */
    synchronized String run(String command) throws Failed {
        try {
            stream.write(command + "\n" + "echo " + marker + "\n");
        } catch (Exception e) {
            throw new Failed("could not write to the held shell", e, false);
        }
        StringBuilder out = new StringBuilder();
        try {
            while (true) {
                AdbShellPacket packet = stream.read();
                if (packet instanceof AdbShellPacket.StdOut stdout) {
                    out.append(new String(stdout.getPayload()));
                    String finished = outputBefore(out.toString(), marker);
                    if (finished != null) {
                        return finished;
                    }
                } else if (packet instanceof AdbShellPacket.Exit) {
                    throw new Failed("the held shell exited", null, true);
                }
                // StdError is drained but not returned — Dadb.shell()'s getOutput() is stdout only, and this
                // has to stay a drop-in for it or every caller's parsing shifts underneath them.
            }
        } catch (Failed e) {
            throw e;
        } catch (Exception e) {
            throw new Failed("the held shell stopped answering", e, true);
        }
    }

    /**
     * The command's output, or {@code null} if the marker has not fully arrived yet.
     *
     * <p>Pure, and separate, because it is the one part of this class a test can reach without a device — and
     * the part with an off-by-one worth pinning: the trailing newline the marker's own {@code echo} emits must
     * be seen before the output is complete, or a marker split across two packets reads as a short frame.
     */
    static String outputBefore(String buffered, String marker) {
        int at = buffered.indexOf(marker);
        if (at < 0 || buffered.indexOf('\n', at) < 0) {
            return null;
        }
        return buffered.substring(0, at);
    }

    @Override
    public void close() {
        try {
            stream.close();
        } catch (Exception ignored) {
            // best-effort teardown
        }
    }

    /**
     * A held shell failed. {@code delivered} is the part callers act on: {@code false} means the command
     * provably never reached the device, so retrying it on a fresh shell cannot repeat a side effect. When the
     * write succeeded and the read failed we cannot know, so it is {@code true} and nothing is retried — a
     * duplicated tap is worse than a surfaced error.
     */
    static final class Failed extends Exception {

        private final boolean delivered;

        Failed(String message, Throwable cause, boolean delivered) {
            super(message + (cause == null ? "" : ": " + cause.getMessage()), cause);
            this.delivered = delivered;
        }

        boolean delivered() {
            return delivered;
        }
    }
}
