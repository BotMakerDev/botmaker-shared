package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** {@link Spawn#run} for this package: a command that outlives its timeout is a failure with a sentence, never null. */
final class Commands {

    /** The exit status reported for a command killed at its timeout. */
    static final int TIMED_OUT = -1;

    private Commands() {}

    static Spawn.Completed run(Duration timeout, List<String> command) throws IOException, InterruptedException {
        Spawn.Completed done = Spawn.run(timeout, command);
        return done != null ? done : new Spawn.Completed(TIMED_OUT,
                program(command) + " didn't finish within " + timeout.toSeconds() + " s.");
    }

    /** The program {@code command} runs: past {@code env} and its {@code NAME=value} settings, as {@link Tart} runs. */
    static String program(List<String> command) {
        if (!command.getFirst().endsWith("/env")) return command.getFirst();
        return command.stream().skip(1).filter(arg -> !arg.contains("=")).findFirst().orElse(command.getFirst());
    }

    static Spawn.Completed run(Duration timeout, String... command) throws IOException, InterruptedException {
        return run(timeout, List.of(command));
    }

    /** Throws {@code what} with the command's own output when it failed. */
    static void require(Spawn.Completed done, String what) throws IOException {
        if (!done.ok()) throw new IOException(what + ": " + done.output().strip());
    }
}
