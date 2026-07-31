package com.botmaker.shared.launch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>B7's gate.</b> A spawned child whose stdout/stderr nobody drains blocks on {@code write()} the moment the
 * OS pipe buffer fills — 64 KB on Linux, 4 KB on some Windows configurations. The child does not crash and
 * does not exit; it simply stops, forever, holding whatever it had done half-done.
 *
 * <p>That is invisible from here, which is what makes it expensive: the symptom is <em>the game hung on
 * startup</em>, several layers away from the {@code ProcessBuilder} that caused it. Five sites in this module
 * spawn without draining or redirecting ({@code GameLauncher.exe}, {@code tryStart}, the two
 * {@code isProcessRunning} probes, {@code SpectacleCapture}); the two that are correct —
 * {@code EmulatorLauncher} and {@code UriLauncher} — already show the pattern the other five need.
 *
 * <p>The chatty child here writes ~256 KB, comfortably past every buffer size in play. A drained or
 * {@code DISCARD}-redirected spawn lets it run to completion in milliseconds; an undrained one wedges it and
 * this test times out.
 */
@DisabledOnOs(OS.WINDOWS) // uses a POSIX shell to produce the chatty child; the fix is platform-neutral
class ProcessSpawnStreamTest {

    /** Well past the 64 KB pipe buffer, small enough to be instant when someone is reading. */
    private static final int CHATTY_BYTES = 256 * 1024;

    private static final String SH = "/bin/sh";

    /** A child that floods stdout, then exits 0. */
    private static String floodStdout() {
        return "yes botmaker | head -c " + CHATTY_BYTES;
    }

    /** A child that floods stderr, then exits 0. */
    private static String floodStderr() {
        return "yes botmaker | head -c " + CHATTY_BYTES + " 1>&2";
    }

    @Test
    @Disabled("B7 is unfixed: verified red on this commit (the child wedges at 64 KB and exe() leaves it there). "
            + "Delete this line in Phase 4 with S6's fix.")
    void exeDoesNotWedgeAChattyChildOnStdout() throws Exception {
        Process p = GameLauncher.exe(SH, "-c", floodStdout());
        assertExitsPromptly(p, "stdout");
    }

    @Test
    @Disabled("B7, as above — re-enable with S6 in Phase 4.")
    void exeDoesNotWedgeAChattyChildOnStderr() throws Exception {
        Process p = GameLauncher.exe(SH, "-c", floodStderr());
        assertExitsPromptly(p, "stderr");
    }

    @Test
    @Disabled("B7, as above — re-enable with S6 in Phase 4.")
    void exeDoesNotWedgeAChildChattyOnBothPipes() throws Exception {
        Process p = GameLauncher.exe(SH, "-c", floodStdout() + "; " + floodStderr());
        assertExitsPromptly(p, "both pipes");
    }

    /**
     * The reference: this is what the five sites must become. Kept as a live (never-disabled) test so the
     * pattern itself stays proven — if {@code DISCARD} ever stopped working, the fix would be built on sand.
     */
    @Test
    void theDiscardPatternTheFixMustApplyDoesNotWedge() throws Exception {
        Process p = new ProcessBuilder(SH, "-c", floodStdout() + "; " + floodStderr())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertExitsPromptly(p, "DISCARD reference");
    }

    /** The other correct shape: read the pipe rather than discarding it, when the output is wanted. */
    @Test
    void theDrainPatternTheProbesMustApplyDoesNotWedge() throws Exception {
        Process p = new ProcessBuilder(SH, "-c", floodStdout())
                .redirectErrorStream(true)
                .start();
        byte[] out = p.getInputStream().readAllBytes();
        assertExitsPromptly(p, "drain reference");
        assertTrue(out.length >= CHATTY_BYTES, "the drain read " + out.length + " of " + CHATTY_BYTES + " bytes");
    }

    private static void assertExitsPromptly(Process p, String what) throws InterruptedException, IOException {
        boolean exited = p.waitFor(15, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        assertTrue(exited, "the child spawned with an undrained " + what
                + " pipe never exited — it is blocked in write(), which is B7. "
                + "The caller sees a game that started and then froze.");
    }
}
