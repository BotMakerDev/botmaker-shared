package com.botmaker.shared.emulator.checks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs one script as root: {@code pkexec /bin/sh -s}, the script on its stdin. The desktop's polkit agent asks
 * for the password in its own window; BotMaker never sees, reads or stores it, and never opens a terminal. One
 * Fix is one call, so the password is asked once per Fix.
 */
public final class RootScript {

    /** How a run ended, from {@code pkexec}'s exit status. */
    public enum Outcome {
        DONE("done", "Done"),
        /** The user closed the password window: nothing ran. */
        CANCELLED("cancelled", "Cancelled, nothing changed"),
        /** No polkit agent answered, or there is no {@code pkexec}: nothing ran. */
        NO_AGENT("no_agent", "No password window could open: run the commands by hand"),
        FAILED("failed", "Failed"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        Outcome(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Outcome fromId(String id) {
            for (Outcome outcome : values()) {
                if (outcome.id.equals(id)) return outcome;
            }
            return UNKNOWN;
        }
    }

    private RootScript() {}

    /** The command the script is piped into. */
    public static List<String> command() {
        return List.of("pkexec", "/bin/sh", "-s");
    }

    /** pkexec's exit status read as an outcome: 126 the user dismissed the dialog, 127 no agent or no auth. */
    public static Outcome outcomeOf(int exit) {
        return switch (exit) {
            case 0 -> Outcome.DONE;
            case 126 -> Outcome.CANCELLED;
            case 127 -> Outcome.NO_AGENT;
            default -> exit > 0 ? Outcome.FAILED : Outcome.UNKNOWN;
        };
    }

    /**
     * Runs {@code script} as root and waits for it. Every line it prints (stdout and stderr together) goes to
     * {@code line}, in order; the {@code STEP n/m …} lines are its progress, the others what to show when it
     * fails. Blocks: call it off the UI thread.
     */
    public static Outcome run(String script, Consumer<String> line) {
        return run(command(), script, line);
    }

    /**
     * {@link #run(String, Consumer)} through another command: a test runs {@code /bin/sh -s} as the user.
     *
     * <p>The script is prefixed with a line printing {@link #STARTED}, kept from the caller: a shell that
     * started may itself exit 126 or 127 (a command not found under {@code set -e}) after changing files, so
     * those statuses mean "nothing ran" only when the marker never came.
     */
    static Outcome run(List<String> command, String script, Consumer<String> line) {
        boolean[] started = {false};
        Outcome outcome = runRaw(command, "echo " + STARTED + "\n" + script, text -> {
            if (!started[0] && text.equals(STARTED)) {
                started[0] = true;
            } else if (line != null) {
                line.accept(text);
            }
        });
        boolean nothingRan = outcome == Outcome.CANCELLED || outcome == Outcome.NO_AGENT;
        return started[0] && nothingRan ? Outcome.FAILED : outcome;
    }

    /** The line a script prints first, once the shell runs it as root. */
    static final String STARTED = "BOTMAKER_STARTED";

    private static Outcome runRaw(List<String> command, String script, Consumer<String> line) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return Outcome.NO_AGENT;
        }
        try {
            try (OutputStream in = process.getOutputStream()) {
                in.write(script.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The shell exited before reading it all (pkexec refused): its exit status says why.
            }
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String text; (text = out.readLine()) != null; ) {
                    if (line != null) line.accept(text);
                }
            }
            return outcomeOf(process.waitFor());
        } catch (IOException e) {
            return Outcome.UNKNOWN;
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            return Outcome.UNKNOWN;
        }
    }
}
