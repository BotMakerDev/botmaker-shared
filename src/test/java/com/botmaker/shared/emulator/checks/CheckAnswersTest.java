package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.checks.RootScript.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckAnswersTest {

    private static final CheckResult OK = new CheckResult(EmulatorCheck.INTERNET, CheckState.OK, "online", "",
            false, false, List.of(), null);

    @Test
    void theEndSaysWhetherItTook() {
        Fix fix = new Fix(EmulatorCheck.INTERNET, List.of(), null);
        assertEquals("Internet inside Android: done.", CheckAnswers.ended(fix,
                new FixRunner.Progress(1, 1, "", Outcome.DONE, false, List.of())));
        assertEquals("Internet inside Android: the fix ran, but the check still fails.", CheckAnswers.ended(fix,
                new FixRunner.Progress(1, 1, "", Outcome.DONE, true, List.of())));
        assertEquals("Internet inside Android: Cancelled, nothing changed.", CheckAnswers.ended(fix,
                new FixRunner.Progress(1, 1, "", Outcome.CANCELLED, false, List.of())));
    }

    @Test
    void anEmulatorWithNoChecksSaysSo() {
        assertEquals("BotMaker has no checks for this emulator.",
                CheckAnswers.answer(List.of(), r -> false, FixRunner.of("check-answers-test-none")));
    }

    @Test
    void aFixOfACheckThatPassesIsRefusedAndTheUnknownOneNamesTheChecks() {
        FixRunner runner = FixRunner.of("check-answers-test-refused");
        CheckAnswers.Said passing = CheckAnswers.fix("internet", false, List.of(OK), r -> Optional.empty(),
                Optional::empty, runner, "device checks");
        assertTrue(passing.refused());
        assertTrue(passing.text().contains("does not fail"), passing.text());

        CheckAnswers.Said unknown = CheckAnswers.fix("nonsense", false, List.of(OK), r -> Optional.empty(),
                Optional::empty, runner, "device checks");
        assertTrue(unknown.refused());
        assertTrue(unknown.text().endsWith("they are internet."), unknown.text());
        assertFalse(runner.running());
    }
}
