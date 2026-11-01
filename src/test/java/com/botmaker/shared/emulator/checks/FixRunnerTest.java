package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.checks.FixRunner.Progress;
import com.botmaker.shared.emulator.checks.RootScript.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One fix at a time, cancel only before root, and the check read again at the end. */
class FixRunnerTest {

    private static final CheckResult STILL_BAD = CheckResult.fixable(EmulatorCheck.INTERNET, "no", "fix");
    private static final CheckResult GOOD = CheckResult.ok(EmulatorCheck.INTERNET, "yes");

    /** Runs on the caller's thread, so a test reads the reports as soon as start returns. */
    private static FixRunner inline(BiFunction<String, Consumer<String>, Outcome> root) {
        return new FixRunner(root, Runnable::run);
    }

    private static Fix asRoot(CheckResult after) {
        return new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsRoot("root", () -> "script")), () -> after);
    }

    @Test
    void aSecondStartIsRefusedWhileOneRuns() throws Exception {
        CountDownLatch inStep = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        FixRunner runner = new FixRunner((s, l) -> Outcome.DONE, task -> Thread.ofVirtual().start(task));
        Fix slow = new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsUser("slow", words -> {
            inStep.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        })), null);
        assertTrue(runner.start(slow, progress -> {
            if (progress.done()) ended.countDown();
        }));
        assertTrue(inStep.await(5, TimeUnit.SECONDS));
        assertTrue(runner.running());
        assertFalse(runner.start(slow, progress -> { }), "one fix at a time per emulator");
        release.countDown();
        assertTrue(ended.await(5, TimeUnit.SECONDS));
        assertFalse(runner.running());
        assertTrue(runner.start(new Fix(EmulatorCheck.INTERNET, List.of(), null), progress -> { }),
                "free again once it ended");
    }

    @Test
    void aRowReactingToTheLastReportMayStartAnother() {
        FixRunner runner = inline((s, l) -> Outcome.DONE);
        List<Boolean> again = new ArrayList<>();
        runner.start(asRoot(GOOD), progress -> {
            if (progress.done()) again.add(runner.start(new Fix(EmulatorCheck.INTERNET, List.of(), null), p -> { }));
        });
        assertEquals(List.of(true), again);
    }

    @Test
    void cancelBeforeRootMeansNoRootCall() {
        List<String> scripts = new ArrayList<>();
        FixRunner runner = inline((script, line) -> {
            scripts.add(script);
            return Outcome.DONE;
        });
        boolean[] honoured = {false};
        Fix fix = new Fix(EmulatorCheck.ARM_TRANSLATOR, List.of(
                new Fix.AsUser("download", words -> {
                    honoured[0] = runner.cancel();
                    return null;
                }),
                new Fix.AsRoot("install", () -> "script")), () -> STILL_BAD);
        List<Progress> reports = new ArrayList<>();
        runner.start(fix, reports::add);
        assertTrue(honoured[0]);
        assertEquals(List.of(), scripts);
        assertEquals(Outcome.CANCELLED, reports.getLast().outcome());
        assertFalse(reports.getLast().stillFailing(), "nothing ran, so nothing to compare");
    }

    @Test
    void cancelIsRefusedOnceRootStarted() {
        boolean[] honoured = {true};
        FixRunner[] holder = new FixRunner[1];
        holder[0] = inline((script, line) -> {
            honoured[0] = holder[0].cancel();
            return Outcome.DONE;
        });
        List<Progress> reports = new ArrayList<>();
        holder[0].start(new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsRoot("root", () -> "script"),
                new Fix.AsUser("after", words -> null)), () -> GOOD), reports::add);
        assertFalse(honoured[0]);
        assertEquals(Outcome.DONE, reports.getLast().outcome());
        assertEquals(2, reports.getLast().step(), "and it ran on to its end");
    }

    @Test
    void aCancelFromALastRunDoesNotStopTheNext() {
        FixRunner runner = inline((s, l) -> Outcome.DONE);
        runner.start(new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsUser("a", words -> {
            runner.cancel();
            return null;
        })), null), progress -> { });
        List<Progress> reports = new CopyOnWriteArrayList<>();
        runner.start(asRoot(GOOD), reports::add);
        assertEquals(Outcome.DONE, reports.getLast().outcome());
    }

    @Test
    void theCheckReadAgainSaysWhetherItTook() {
        FixRunner runner = inline((s, l) -> Outcome.DONE);
        List<Progress> bad = new ArrayList<>();
        runner.start(asRoot(STILL_BAD), bad::add);
        assertEquals(Outcome.DONE, bad.getLast().outcome());
        assertTrue(bad.getLast().stillFailing());

        List<Progress> good = new ArrayList<>();
        runner.start(asRoot(GOOD), good::add);
        assertFalse(good.getLast().stillFailing());
    }

    @Test
    void aCancelledPasswordEndsTheRunAndSkipsTheRest() {
        boolean[] later = {false};
        FixRunner runner = inline((s, l) -> Outcome.CANCELLED);
        List<Progress> reports = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsRoot("root", () -> "script"),
                new Fix.AsUser("later", words -> {
                    later[0] = true;
                    return null;
                })), () -> STILL_BAD), reports::add);
        assertEquals(Outcome.CANCELLED, reports.getLast().outcome());
        assertFalse(later[0]);
        assertFalse(reports.getLast().stillFailing());
    }

    @Test
    void aRootStepWithNoScriptAsksNoPassword() {
        List<String> scripts = new ArrayList<>();
        FixRunner runner = inline((script, line) -> {
            scripts.add(script);
            return Outcome.DONE;
        });
        List<Progress> reports = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.WAYDROID_RUNNING, List.of(new Fix.AsRoot("service", () -> null),
                new Fix.AsUser("session", words -> null)), () -> GOOD), reports::add);
        assertEquals(List.of(), scripts);
        assertEquals(Outcome.DONE, reports.getLast().outcome());
    }

    @Test
    void stepLinesAreProgressAndOthersAreOutput() {
        FixRunner runner = inline((script, line) -> {
            line.accept("STEP 1/2 one");
            line.accept("oops");
            line.accept("STEP 2/2 two");
            return Outcome.FAILED;
        });
        List<String> tapped = new ArrayList<>();
        List<Progress> reports = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsRoot("root", () -> "script", tapped::add)),
                null), reports::add);
        assertEquals(List.of("root", "one", "two", "two"), reports.stream().map(Progress::words).toList());
        assertEquals(List.of("oops"), reports.getLast().output());
        assertEquals(List.of("oops"), tapped);
        assertEquals(Outcome.FAILED, reports.getLast().outcome());
    }

    @Test
    void aFailingUserStepOrAThrownOneIsFailedWithItsWords() {
        FixRunner runner = inline((s, l) -> Outcome.DONE);
        List<Progress> said = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsUser("a", words -> "no download"),
                new Fix.AsRoot("root", () -> "script")), null), said::add);
        assertEquals(Outcome.FAILED, said.getLast().outcome());
        assertEquals(List.of("no download"), said.getLast().output());
        assertEquals(1, said.getLast().step());

        List<Progress> thrown = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsUser("a", words -> {
            throw new IllegalStateException("boom");
        })), null), thrown::add);
        assertEquals(Outcome.FAILED, thrown.getLast().outcome());
        assertEquals(List.of("boom"), thrown.getLast().output());
        assertFalse(runner.running());
    }

    @Test
    void anErrorStillEndsTheRunAndFreesTheRunner() {
        FixRunner runner = inline((s, l) -> Outcome.DONE);
        List<Progress> reports = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsUser("a", words -> {
            throw new NoClassDefFoundError();
        })), null), reports::add);
        assertEquals(Outcome.FAILED, reports.getLast().outcome());
        assertEquals(List.of("java.lang.NoClassDefFoundError"), reports.getLast().output(), "never the word null");
        assertFalse(runner.running());
    }

    @Test
    void aRootStepWithNoScriptLeavesTheRunCancellable() {
        FixRunner runner = inline((s, l) -> Outcome.DONE);
        boolean[] honoured = {false};
        List<Progress> reports = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.WAYDROID_RUNNING, List.of(new Fix.AsRoot("service", () -> null),
                new Fix.AsUser("session", words -> {
                    honoured[0] = runner.cancel();
                    return null;
                }),
                new Fix.AsUser("after", words -> null)), null), reports::add);
        assertTrue(honoured[0]);
        assertEquals(Outcome.CANCELLED, reports.getLast().outcome());
    }

    @Test
    void cancelOnTheLastStepIsRefused() {
        FixRunner runner = inline((s, l) -> Outcome.DONE);
        boolean[] honoured = {true};
        List<Progress> reports = new ArrayList<>();
        runner.start(new Fix(EmulatorCheck.ADB_BOOTED, List.of(new Fix.AsUser("only", words -> {
            honoured[0] = runner.cancel();
            return null;
        })), null), reports::add);
        assertFalse(honoured[0]);
        assertEquals(Outcome.DONE, reports.getLast().outcome());
    }

    @Test
    void oneRunnerPerEmulator() {
        assertEquals(FixRunner.of("waydroid@a"), FixRunner.of("waydroid@a"));
        assertFalse(FixRunner.of("waydroid@a").equals(FixRunner.of("avd@b")));
    }
}
