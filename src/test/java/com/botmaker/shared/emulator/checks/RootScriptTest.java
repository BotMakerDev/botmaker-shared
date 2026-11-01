package com.botmaker.shared.emulator.checks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** pkexec's exit statuses, and a script run through {@code /bin/sh -s} as the user. */
class RootScriptTest {

    @Test
    void theExitStatusSaysWhatHappened() {
        assertEquals(RootScript.Outcome.DONE, RootScript.outcomeOf(0));
        assertEquals(RootScript.Outcome.CANCELLED, RootScript.outcomeOf(126));
        assertEquals(RootScript.Outcome.NO_AGENT, RootScript.outcomeOf(127));
        assertEquals(RootScript.Outcome.FAILED, RootScript.outcomeOf(1));
        assertEquals(RootScript.Outcome.UNKNOWN, RootScript.outcomeOf(-1));
        assertEquals("Cancelled, nothing changed", RootScript.Outcome.CANCELLED.displayName());
        assertEquals(RootScript.Outcome.UNKNOWN, RootScript.Outcome.fromId("nope"));
        assertEquals(List.of("pkexec", "/bin/sh", "-s"), RootScript.command());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aCallerThatThrowsDoesNotEndTheRunWhileTheScriptGoesOn() {
        List<String> lines = new ArrayList<>();
        assertEquals(RootScript.Outcome.DONE, RootScript.run(List.of("/bin/sh", "-s"), "echo one\necho two\n", text -> {
            lines.add(text);
            throw new IllegalStateException("a row gone");
        }));
        assertEquals(List.of("one", "two"), lines);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void everyLineReachesTheCallerInOrder() {
        List<String> lines = new ArrayList<>();
        assertEquals(RootScript.Outcome.DONE, RootScript.run(List.of("/bin/sh", "-s"),
                "echo 'STEP 1/2 one'\necho oops >&2\necho 'STEP 2/2 two'\n", lines::add));
        assertEquals(List.of("STEP 1/2 one", "oops", "STEP 2/2 two"), lines);
        assertEquals(RootScript.Outcome.CANCELLED, RootScript.run(List.of("/bin/sh", "-c", "exit 126"), "echo hi\n",
                null), "pkexec refused before the shell started");
        assertEquals(RootScript.Outcome.FAILED, RootScript.run(List.of("/bin/sh", "-s"), "no-such-command\n", null),
                "127 from the script itself, after it started, changed what it changed");
        assertEquals(RootScript.Outcome.NO_AGENT,
                RootScript.run(List.of("/no/such/pkexec"), "echo hi\n", null), "no pkexec at all");
    }
}
