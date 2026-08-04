package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code /proc} readings both launch probes now filter on. Asserted against real children rather than a
 * fixture, because the whole class is about what the kernel reports — a fake would only prove that the parser
 * parses its own output.
 */
@DisabledOnOs(OS.WINDOWS) // No /proc; every reading there answers "no evidence", which is its own assertion below.
class ProcessOriginTest {

    /** A live {@code sleep} with {@code DISPLAY} set to {@code display} (or unset when {@code null}). */
    private static Process sleeperOn(String display) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sleep", "30");
        if (display == null) {
            pb.environment().remove("DISPLAY");
        } else {
            pb.environment().put("DISPLAY", display);
        }
        return pb.start();
    }

    @Test
    void aProcessDisplayIsReadOffItsEnvironment() throws Exception {
        Process p = sleeperOn(":9");
        try {
            assertEquals(":9", ProcessOrigin.displayOf(p.pid()));
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void aProcessOnAnotherDisplayIsNotOnTheHostDesktop() throws Exception {
        // Needs a desktop: with no host DISPLAY to compare against, onHostDisplay() deliberately answers "on the
        // host" (see anUnreadableOrDisplaylessProcessCountsAsTheHost), so "elsewhere" is indistinguishable here.
        assumeTrue(ProcessOrigin.hostDisplay() != null, "no host DISPLAY to be elsewhere from");
        // ":9" is chosen to differ from any plausible host DISPLAY.
        Process elsewhere = sleeperOn(":9");
        Process here = sleeperOn(ProcessOrigin.hostDisplay());
        try {
            assertFalse(ProcessOrigin.onHostDisplay(elsewhere.toHandle()),
                    "a launcher on a private display can't swallow a launch aimed at that same display");
            assertTrue(ProcessOrigin.onHostDisplay(here.toHandle()),
                    "a process on our own DISPLAY is exactly what the refusal is about");
        } finally {
            elsewhere.destroyForcibly();
            here.destroyForcibly();
        }
    }

    @Test
    void anUnreadableOrDisplaylessProcessCountsAsTheHost() throws Exception {
        // The direction that preserves behaviour where the reading is unavailable: no evidence of being elsewhere
        // is not evidence of being elsewhere. A dead pid is the extreme case of "nothing readable".
        Process none = sleeperOn(null);
        try {
            assertNull(ProcessOrigin.displayOf(none.pid()));
            assertTrue(ProcessOrigin.onHostDisplay(none.toHandle()));
        } finally {
            none.destroyForcibly();
        }
        assertFalse(ProcessOrigin.onHostDisplay(null), "no process is not a process on the host desktop");
    }

    @Test
    void thisJvmIsNotInASessionAndIsNoRemnant() {
        long self = ProcessHandle.current().pid();
        assertNull(ProcessOrigin.sessionIdOf(self), "the test JVM is not a member of a nested session");
        assertFalse(ProcessOrigin.isSessionRemnant(self));
        // A pid that cannot exist reads as "not in a session", never as a remnant — the safe direction, since a
        // remnant is *excluded* from the running evidence.
        assertFalse(ProcessOrigin.isSessionRemnant(-1));
    }

    @Test
    void aDisplayNumberIgnoresTheScreenAndTheHost() {
        assertEquals("1", ProcessOrigin.displayNumber(":1"));
        assertEquals("1", ProcessOrigin.displayNumber(":1.0"));
        assertEquals("0", ProcessOrigin.displayNumber("localhost:0.0"));
        assertNull(ProcessOrigin.displayNumber(""));
        assertNull(ProcessOrigin.displayNumber(null));
        assertNull(ProcessOrigin.displayNumber("nonsense"));
    }

    @Test
    void theSessionUnitPrefixIsTheOneTheReaperWrites() {
        // Single-sourced on purpose: the reaper names units with this prefix and sessionIdOf parses them back.
        assertTrue(ProcessOrigin.SESSION_UNIT_PREFIX.equals("botmaker-sess-"),
                "changing this breaks the orphan sweep and the remnant reading together");
    }

    @Test
    void describeNamesSomethingActionable() throws Exception {
        Process p = sleeperOn(":9");
        try {
            assertEquals(":9", ProcessOrigin.describe(p.toHandle()));
        } finally {
            p.destroyForcibly();
        }
        assertEquals("nowhere", ProcessOrigin.describe(null));
    }
}
