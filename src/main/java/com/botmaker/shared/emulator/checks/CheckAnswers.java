package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.WaydroidPlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The checks and their fixes as an assistant reads them, one row a line: the SDK's {@code setup} and the core's
 * {@code device} tool say the same (2026-11-10). A fix runs in the background on its emulator's one runner in this
 * process, so two never run at once here; how the last one went is kept here for the next read. The runners are a
 * process's own: a fix the core's {@code device} starts and one Studio's Game dialog starts are not kept apart.
 */
public final class CheckAnswers {

    /** What a fix request answers: a sentence, refused when nothing started. */
    public record Said(boolean refused, String text) {
    }

    /** Per emulator's runner: what its last fix is doing, or how it ended with what it printed. */
    private static final Map<FixRunner, String> FIX_LINE = new ConcurrentHashMap<>();

    private CheckAnswers() {}

    /** The one runner of {@code instance}'s emulator; {@code null} is the Waydroid not discovered. */
    public static FixRunner runnerOf(EmulatorInstance instance) {
        boolean waydroid = instance == null || instance.platformId() == PlatformId.WAYDROID;
        return FixRunner.of(waydroid ? WaydroidPlatform.INSTANCE_NAME : instance.identity());
    }

    /** How {@code fix} ended, as its row and the assistant say it. */
    public static String ended(Fix fix, FixRunner.Progress end) {
        String name = fix.check().displayName();
        if (end.outcome() != RootScript.Outcome.DONE) return name + ": " + end.outcome().displayName() + ".";
        return end.stillFailing() ? name + ": the fix ran, but the check still fails." : name + ": done.";
    }

    /** The rows, one a line, under how the last fix went. */
    public static String answer(List<CheckResult> results, Predicate<CheckResult> canFix, FixRunner runner) {
        if (results.isEmpty()) return "BotMaker has no checks for this emulator.";
        List<String> lines = new ArrayList<>();
        String last = FIX_LINE.get(runner);
        if (last != null) lines.add("Last fix: " + last);
        for (CheckResult result : results) lines.add(row(result, canFix.test(result)));
        return String.join("\n", lines);
    }

    private static String row(CheckResult result, boolean canFix) {
        String ok = switch (result.state()) {
            case OK -> "true";
            case FAILED -> "false";
            case AFTER_ABOVE -> "after_the_one_above";
            case INFO -> "info";
            case UNKNOWN -> "unknown";
        };
        String row = result.check().id() + " ok=" + ok + " fix=" + (canFix ? "yes" : "no")
                + (result.undoable() ? " undo=yes" : "");
        if (result.state() == CheckState.AFTER_ABOVE) return row;
        String said = result.reason();
        if (result.failed()) said += " " + (canFix ? result.remedy() : byHand(result));
        return said.isBlank() ? row : row + ": " + said.strip();
    }

    /** What the user does for a failure no fix repairs. */
    public static String byHand(CheckResult result) {
        return result.remedy() + (result.commands().isEmpty() ? "" : " The user runs: "
                + String.join("; ", result.commands())) + (result.link() == null ? "" : " See " + result.link());
    }

    /**
     * Picks the fix {@code check} names in {@code results} and starts it on {@code runner}.
     *
     * @param checksCall the call that reads the rows again, which the answer points to
     */
    public static Said fix(String check, boolean undo, List<CheckResult> results,
                           Function<CheckResult, Optional<Fix>> fixOf, Function<EmulatorCheck, Optional<Fix>> undoFix,
                           FixRunner runner, String checksCall) {
        EmulatorCheck named = EmulatorCheck.fromId(check);
        CheckResult result = results.stream().filter(r -> r.check() == named).findFirst().orElse(null);
        if (result == null) {
            return new Said(true, "\"" + check + "\" is no check on this emulator; they are "
                    + String.join(", ", results.stream().map(r -> r.check().id()).toList()) + ".");
        }
        if (undo) {
            if (!result.undoable()) return new Said(true, named.displayName() + " has no fix to undo.");
            return undoFix.apply(named).map(fix -> started(fix, runner, checksCall))
                    .orElseGet(() -> new Said(true, "No backup is left to restore."));
        }
        if (!result.failed()) return new Said(true, named.displayName() + " does not fail; nothing to fix.");
        return fixOf.apply(result).map(fix -> started(fix, runner, checksCall))
                .orElseGet(() -> new Said(true, named.displayName() + " has no fix: " + result.reason() + " "
                        + byHand(result)));
    }

    /** Started and answered at once: a fix takes minutes and may wait on the user's password. */
    private static Said started(Fix fix, FixRunner runner, String checksCall) {
        String name = fix.check().displayName();
        boolean started = runner.start(fix, progress -> FIX_LINE.put(runner, progress.done() ? printed(fix, progress)
                : name + " is running, step " + progress.step() + "/" + progress.of() + ": " + progress.words()));
        if (!started) {
            return new Said(false, "A fix is already running on this emulator; " + checksCall + " says how it went.");
        }
        return new Said(false, name + "'s fix is running" + (fix.needsRoot()
                ? ": the user's desktop asks for their password" : "") + "; " + checksCall + " says how it went.");
    }

    /** How {@code fix} ended, with what it printed. */
    private static String printed(Fix fix, FixRunner.Progress end) {
        String line = ended(fix, end);
        return end.output().isEmpty() ? line : line + " It printed:\n" + String.join("\n", end.output());
    }
}
