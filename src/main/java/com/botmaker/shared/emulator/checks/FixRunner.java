package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.checks.RootScript.Outcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs one {@link Fix} at a time per emulator, off the caller's thread: a second start while one runs is refused,
 * whichever check's Fix it is, so two root scripts never meet on one Waydroid.
 */
public final class FixRunner {

    /**
     * How a run is going; {@link #outcome} is null until the last report.
     *
     * @param step         which step, from 1
     * @param of           how many
     * @param words        what is happening
     * @param outcome      how it ended, or {@code null} while it runs
     * @param stillFailing it ran to its end, yet the check still fails: the Fix did not take
     * @param output       what its steps said beyond their progress: what to show when it failed
     */
    public record Progress(int step, int of, String words, Outcome outcome, boolean stillFailing,
                           List<String> output) {

        public boolean done() {
            return outcome != null;
        }
    }

    private static final Pattern STEP_LINE = Pattern.compile("STEP \\d+/\\d+ (.*)");

    private static final Map<String, FixRunner> RUNNERS = new ConcurrentHashMap<>();

    private final BiFunction<String, Consumer<String>, Outcome> root;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object cancelLock = new Object();
    private boolean cancelled;
    private boolean pastRoot;
    private boolean lastStep;

    FixRunner(BiFunction<String, Consumer<String>, Outcome> root, Executor executor) {
        this.root = root;
        this.executor = executor;
    }

    /** The runner of the emulator named by {@code key}, its {@code identity()}. */
    public static FixRunner of(String key) {
        return RUNNERS.computeIfAbsent(key, k -> new FixRunner(RootScript::run,
                task -> Thread.ofVirtual().name("emulator-fix").start(task)));
    }

    /**
     * Starts {@code fix}; {@code progress} is called on its thread, the last call with an outcome.
     *
     * @return false, and nothing started, when one is already running
     */
    public boolean start(Fix fix, Consumer<Progress> progress) {
        if (!running.compareAndSet(false, true)) return false;
        synchronized (cancelLock) {
            cancelled = false;
            pastRoot = false;
            lastStep = false;
        }
        try {
            executor.execute(() -> run(fix, progress));
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    public boolean running() {
        return running.get();
    }

    /**
     * Stops the run before its next step. Once a root script has started there is no stopping it, and a run on
     * its last step has no next one.
     *
     * @return whether it will stop
     */
    public boolean cancel() {
        synchronized (cancelLock) {
            if (!running.get() || pastRoot || lastStep) return false;
            cancelled = true;
            return true;
        }
    }

    private void run(Fix fix, Consumer<Progress> progress) {
        List<Fix.Step> steps = fix.steps();
        int of = steps.size();
        List<String> output = new ArrayList<>();
        String[] last = {""};
        int[] at = {0};
        Consumer<String> report = words -> {
            last[0] = words;
            progress.accept(new Progress(at[0], of, words, null, false, List.of()));
        };
        Outcome outcome = Outcome.DONE;
        boolean stillFailing = false;
        try {
            for (int i = 0; i < of && outcome == Outcome.DONE; i++) {
                at[0] = i + 1;
                Fix.Step step = steps.get(i);
                if (stopHere(i == of - 1)) {
                    outcome = Outcome.CANCELLED;
                    break;
                }
                report.accept(step.words());
                outcome = switch (step) {
                    case Fix.AsUser user -> runAsUser(user, report, output);
                    case Fix.AsRoot asRoot -> runAsRoot(asRoot, report, output);
                };
            }
            if (outcome == Outcome.DONE && fix.recheck() != null) {
                CheckResult again = fix.recheck().get();
                stillFailing = again != null && again.failed();
            }
        } catch (Throwable t) {
            // An Error too (a class that won't link): anything else leaves the emulator's Fix buttons refused for good.
            outcome = Outcome.FAILED;
            output.add(t.getMessage() == null ? t.toString() : t.getMessage());
        }
        // Free before the last report: a row that reacts to it by starting another Fix must not be refused.
        running.set(false);
        progress.accept(new Progress(at[0], of, last[0], outcome, stillFailing, List.copyOf(output)));
    }

    /** Whether a cancel stops the run before this step; a cancel during the last one has nothing left to stop. */
    private boolean stopHere(boolean last) {
        synchronized (cancelLock) {
            lastStep = last;
            return cancelled;
        }
    }

    private static Outcome runAsUser(Fix.AsUser step, Consumer<String> report, List<String> output) throws Exception {
        String failure = step.action().run(report);
        if (failure == null) return Outcome.DONE;
        output.add(failure);
        return Outcome.FAILED;
    }

    private Outcome runAsRoot(Fix.AsRoot step, Consumer<String> report, List<String> output) {
        String script = step.script().get();
        if (script == null) return Outcome.DONE;
        synchronized (cancelLock) {
            if (cancelled) return Outcome.CANCELLED;
            pastRoot = true;
        }
        return root.apply(script, line -> {
            Matcher matcher = STEP_LINE.matcher(line);
            if (matcher.matches()) {
                report.accept(matcher.group(1));
            } else {
                output.add(line);
                if (step.output() != null) step.output().accept(line);
            }
        });
    }
}
