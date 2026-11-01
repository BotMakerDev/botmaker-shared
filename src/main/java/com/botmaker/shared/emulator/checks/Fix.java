package com.botmaker.shared.emulator.checks;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What one Fix button does: its steps in order, and how to read the check again afterwards. {@link Fixes} builds
 * them and {@link FixRunner} runs them; nothing runs on building one.
 *
 * @param check   the check this repairs
 * @param steps   in order
 * @param recheck the check read again once the steps ended, or {@code null} when there is nothing to compare
 *                (an Undo puts back a state the check may well dislike)
 */
public record Fix(EmulatorCheck check, List<Step> steps, Supplier<CheckResult> recheck) {

    public Fix {
        steps = List.copyOf(steps);
    }

    /** One step: {@link #words} is what the row shows while it runs. */
    public sealed interface Step permits AsUser, AsRoot {
        String words();
    }

    /** Work as the user: a download, a file of the user's own, adb. */
    public record AsUser(String words, UserAction action) implements Step {}

    /**
     * One script as root, one password window.
     *
     * @param script built when the step starts, so it can use what the steps before it produced; {@code null}
     *               from it means there is nothing to do, and no password is asked
     * @param output the script's lines other than its {@code STEP} ones, or {@code null}
     */
    public record AsRoot(String words, Supplier<String> script, Consumer<String> output) implements Step {

        public AsRoot(String words, Supplier<String> script) {
            this(words, script, null);
        }
    }

    /** A user step's work. */
    @FunctionalInterface
    public interface UserAction {
        /**
         * @param words replaces what the row shows
         * @return null when it worked, else why not, in the user's words
         */
        String run(Consumer<String> words) throws Exception;
    }

    /** Whether a step may ask for the password. */
    public boolean needsRoot() {
        return steps.stream().anyMatch(AsRoot.class::isInstance);
    }

    /** What each step says, in order. */
    public List<String> words() {
        return steps.stream().map(Step::words).toList();
    }
}
