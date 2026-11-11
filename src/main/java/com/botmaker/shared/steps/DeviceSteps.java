package com.botmaker.shared.steps;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * What a piece of work did to a device: each ADB shell command, frame grab, install and push ({@code AdbDevice},
 * {@code AdbTools}) and each window input ({@code NativeControllerFactory}), told to the {@link Recorder} the caller
 * bound around the work ({@link #recording}). The core binds one around each MCP call, so the Assistant window lists a
 * call's steps under it (spec {@code 2026-11-11-assistant-window-design.md}).
 *
 * <p>A {@link ScopedValue}: the work's own thread carries it, and a thread of its own (a plugin's executor) does not
 * unless the work hands it over. A step with no recorder is only counted ({@link #unscoped()}), so a caller can say how
 * many ran elsewhere meanwhile rather than drop them silently. A step inside another one (an install's shell commands)
 * is the outer step's, never told twice.
 */
public final class DeviceSteps {

    /** The longest {@link Step#result()}: the start of what the device said, the rest the tool's own answer. */
    public static final int RESULT_CHARS = 160;

    /**
     * One step: on which device (its endpoint, or "window" for window input), what was sent, whether it worked, the
     * start of what came back or why it failed, when it began and how long it took.
     */
    public record Step(String device, String command, boolean ok, String result, long atMillis, long millis) {
    }

    /** Hears a scope's steps, on the thread that ran each, and its progress lines, on any thread. */
    public interface Recorder {
        void step(Step step);

        /** A long step's progress: {@code done} of {@code total} (bytes, for an upload). */
        void progress(String device, String command, long done, long total);
    }

    /** A step's work, which may throw. */
    @FunctionalInterface
    public interface Work<T, X extends Exception> {
        T run() throws X;
    }

    /** Where a long step reports its progress from any thread: the recorder bound where it began, or nothing. */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (done, total) -> {
        };

        void report(long done, long total);
    }

    private static final ScopedValue<Recorder> RECORDER = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> INSIDE = ScopedValue.newInstance();
    private static final AtomicLong UNSCOPED = new AtomicLong();

    private DeviceSteps() {
    }

    /** Runs {@code work} with its steps told to {@code recorder}, on this thread and any it hands the scope to. */
    public static <T, X extends Throwable> T recording(Recorder recorder, ScopedValue.CallableOp<? extends T, X> work)
            throws X {
        return ScopedValue.where(RECORDER, recorder).call(work);
    }

    /** How many steps ran with no recorder since this JVM started: their difference over a call is "elsewhere". */
    public static long unscoped() {
        return UNSCOPED.get();
    }

    /**
     * Runs {@code work} as one step on {@code device}, told to the bound recorder as it ends: {@code said} reads its
     * result, a throw its message (and is rethrown). Nothing is told inside another step, and nothing but a count with
     * no recorder.
     */
    public static <T, X extends Exception> T step(String device, String command, Work<T, X> work,
                                                  ResultText<? super T> said) throws X {
        return step(device, command, work, said, result -> true);
    }

    /** {@link #step}, where {@code worked} says whether a result that came back is a failure the device reported. */
    public static <T, X extends Exception> T step(String device, String command, Work<T, X> work,
                                                  ResultText<? super T> said, Predicate<? super T> worked) throws X {
        if (INSIDE.isBound()) return work.run();
        if (!RECORDER.isBound()) {
            UNSCOPED.incrementAndGet();
            return ScopedValue.where(INSIDE, true).call(work::run);
        }
        Recorder recorder = RECORDER.get();
        long at = System.currentTimeMillis();
        long began = System.nanoTime();
        T result;
        try {
            result = ScopedValue.where(INSIDE, true).call(work::run);
        } catch (Exception | Error e) {
            tell(recorder, new Step(device, command, false, clip(e.getMessage() == null ? e.toString() : e.getMessage()),
                    at, millisSince(began)));
            throw e;
        }
        tell(recorder, new Step(device, command, worked.test(result), clip(said.text(result)), at, millisSince(began)));
        return result;
    }

    /** {@link #step} for work that returns nothing. */
    public static <X extends Exception> void step(String device, String command, Work<Void, X> work) throws X {
        step(device, command, work, result -> "");
    }

    /** How a step's result reads in one line. */
    @FunctionalInterface
    public interface ResultText<T> {
        String text(T result);
    }

    /** Where {@code command}'s progress on {@code device} goes from any thread, taken where the step begins. */
    public static Progress progress(String device, String command) {
        if (!RECORDER.isBound()) return Progress.NONE;
        Recorder recorder = RECORDER.get();
        return (done, total) -> {
            try {
                recorder.progress(device, command, done, total);
            } catch (RuntimeException e) {
                // A recorder that fails never fails the work it watches.
            }
        };
    }

    /** {@code text}'s first line that says something, cut at {@link #RESULT_CHARS}. */
    public static String clip(String text) {
        if (text == null) return "";
        for (String line : text.split("\\R")) {
            String kept = line.strip();
            if (kept.isEmpty()) continue;
            return kept.length() <= RESULT_CHARS ? kept : kept.substring(0, RESULT_CHARS - 1) + "…";
        }
        return "";
    }

    private static void tell(Recorder recorder, Step step) {
        try {
            recorder.step(step);
        } catch (RuntimeException e) {
            // A recorder that fails never fails the work it watches.
        }
    }

    private static long millisSince(long began) {
        return (System.nanoTime() - began) / 1_000_000;
    }
}
