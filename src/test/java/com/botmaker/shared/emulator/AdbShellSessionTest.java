package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The framing rule of the held shell: how {@link AdbShellSession} decides a command has finished.
 *
 * <p>Only the pure half is reachable without a device, and it is the half that can be subtly wrong — a
 * marker that arrives split across two ADB packets is the normal case, not the edge case, because the marker
 * follows the command's own output through the same pipe.
 */
class AdbShellSessionTest {

    private static final String MARKER = "__bmdeadbeef__";

    @Test
    void outputStopsAtTheMarker() {
        assertEquals("hello\n", AdbShellSession.outputBefore("hello\n" + MARKER + "\n", MARKER));
    }

    /**
     * The read loop calls this after every packet, so it must say "not yet" for every prefix of a completed
     * response — including the one where the marker is entirely present but its newline has not arrived.
     */
    @Test
    void aPartialMarkerIsNotAResult() {
        String complete = "hello\n" + MARKER + "\n";
        for (int i = 0; i < complete.length(); i++) {
            assertNull(AdbShellSession.outputBefore(complete.substring(0, i), MARKER),
                    "prefix of length " + i + " must not read as finished");
        }
        assertEquals("hello\n", AdbShellSession.outputBefore(complete, MARKER));
    }

    /** A command that printed nothing — every {@code input tap} — is an empty result, not a hang. */
    @Test
    void aSilentCommandYieldsEmptyOutput() {
        assertEquals("", AdbShellSession.outputBefore(MARKER + "\n", MARKER));
    }

    /** Output with no trailing newline must not swallow its last line into the marker. */
    @Test
    void outputWithNoTrailingNewlineIsKeptWhole() {
        assertEquals("no newline here", AdbShellSession.outputBefore("no newline here" + MARKER + "\n", MARKER));
    }

    /**
     * Multi-line output (this is what {@code pm list packages} looks like) stops at the first marker, and the
     * newlines inside it must not be mistaken for the marker's own.
     */
    @Test
    void multiLineOutputIsReturnedWhole() {
        String output = "package:com.a\npackage:com.b\npackage:com.c\n";
        assertEquals(output, AdbShellSession.outputBefore(output + MARKER + "\n", MARKER));
    }

    /**
     * The reason the marker is randomised per session rather than a constant: a command's output is not ours
     * to control, and a fixed marker appearing inside it would truncate the result <em>and</em> leave the
     * remainder to corrupt the next command. With a random marker this case is vanishing; the test states the
     * behaviour so the reasoning is not lost.
     */
    @Test
    void aMarkerAppearingInOutputWouldTruncateThere() {
        assertEquals("oops ", AdbShellSession.outputBefore("oops " + MARKER + " in output\n", MARKER));
    }
}
