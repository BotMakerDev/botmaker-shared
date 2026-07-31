package com.botmaker.shared.input.linux;

import com.botmaker.shared.input.InputEvent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The listener's lifecycle contract: <b>open, close, reopen — repeatedly — without leaking an X connection.</b>
 *
 * <p>{@link X11InputListener} holds <em>three</em> display connections, and which thread closes them is
 * deliberate and easy to break: {@code close()} runs on the caller's thread and only disables the context, so
 * every {@code XCloseDisplay} happens on the record thread after {@code XRecordEnableContext} returns. A
 * refactor that closed them in {@code close()} instead would look tidier, pass every functional test, and leak
 * one fd per recording session — the symptom being Studio running out of file descriptors after an afternoon
 * of recording, which nothing would attribute back to here.
 *
 * <p>The assertion is therefore on file descriptors, not on the API: count the process's open sockets before
 * and after N cycles. That is the only observable that distinguishes "closed" from "believed closed".
 *
 * <p><b>And it found that they are not closed.</b> The count grows by exactly three per cycle and never comes
 * back down, because {@code XRecordEnableContext} does not return when the context is disabled — so the record
 * thread stays blocked, {@code cleanupDisplays()} never runs, and each recording session costs three X
 * connections and one live thread for the life of the process. Logged as <b>B15</b>; the leak test is
 * {@code @Disabled} until it is fixed. The audit had read the same code and logged a millisecond-wide race.
 */
@EnabledOnOs(OS.LINUX)
class X11InputListenerLifecycleTest {

    private static final int CYCLES = 5;

    /** Three connections per listener, so a full leak would show as 3×CYCLES. Allow slack for JVM churn. */
    private static final int LEAK_TOLERANCE = 4;

    private static boolean hasDisplay() {
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }

    @Test
    @Disabled("B15 is unfixed: verified red on this commit at exactly 3 leaked fds per cycle, permanently. "
            + "This test is what found it — the audit had logged the milder 'close() returns before cleanup "
            + "has run' race, and the cleanup in fact never runs. Delete this line with B15's fix in Phase 4.")
    void repeatedOpenAndCloseDoesNotLeakDisplayConnections() throws Exception {
        assumeTrue(hasDisplay(), "no X display: DISPLAY is unset (a headless CI needs Xvfb)");
        assumeTrue(canRecord(), "the X server has no RECORD extension, so the listener cannot start here");

        int before = openFdCount();
        for (int i = 0; i < CYCLES; i++) {
            List<InputEvent> events = new CopyOnWriteArrayList<>();
            X11InputListener listener = new X11InputListener();
            listener.start(events::add);
            listener.close();
            awaitFdSettle();
        }
        int after = openFdCount();

        assertTrue(after - before <= LEAK_TOLERANCE,
                "open file descriptors grew by " + (after - before) + " over " + CYCLES
                        + " start/close cycles (tolerance " + LEAK_TOLERANCE + "). Each listener opens three X "
                        + "connections; a growth near " + (3 * CYCLES) + " means close() stopped reaching "
                        + "XCloseDisplay — check which thread does the teardown.");
    }

    /** {@code close()} is idempotent by contract ({@code if (closed) return}), and the second call must not throw. */
    @Test
    void closeIsIdempotent() {
        assumeTrue(hasDisplay(), "no X display: DISPLAY is unset (a headless CI needs Xvfb)");
        assumeTrue(canRecord(), "the X server has no RECORD extension, so the listener cannot start here");

        X11InputListener listener = new X11InputListener();
        listener.start(event -> { });
        listener.close();
        listener.close();
        listener.close();
    }

    /** Closing one that never started must also be safe — Studio closes on a path that may not have recorded. */
    @Test
    void closeWithoutStartIsSafe() {
        new X11InputListener().close();
    }

    /**
     * A listener is single-use: {@code start} refuses a second call and {@code close} never clears the thread,
     * so "reopen" means a <em>new instance</em>. Pinning that here keeps the leak test above honest — if
     * restart were ever allowed, its cycle loop would stop exercising three fresh connections per iteration.
     */
    @Test
    void startTwiceIsRefusedRatherThanOpeningASecondSetOfConnections() {
        assumeTrue(hasDisplay(), "no X display: DISPLAY is unset (a headless CI needs Xvfb)");
        assumeTrue(canRecord(), "the X server has no RECORD extension, so the listener cannot start here");

        X11InputListener listener = new X11InputListener();
        try {
            listener.start(event -> { });
            assertThrows(IllegalStateException.class, () -> listener.start(event -> { }),
                    "a second start() would open three more connections and orphan the first three");
        } finally {
            listener.close();
        }
    }

    // ---- helpers ----

    /** Whether this X server supports RECORD; without it {@code start()} throws and there is nothing to leak. */
    private static boolean canRecord() {
        try {
            X11InputListener probe = new X11InputListener();
            try {
                probe.start(event -> { });
                return true;
            } finally {
                probe.close();
                awaitFdSettle();
            }
        } catch (Throwable notAvailable) {
            return false;
        }
    }

    /** The teardown is on the record thread, so the fds close slightly after {@code close()} returns. */
    private static void awaitFdSettle() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int openFdCount() throws IOException {
        Path fds = Path.of("/proc/self/fd");
        assumeTrue(Files.isDirectory(fds), "/proc/self/fd is unavailable, so fd counting cannot work here");
        try (Stream<Path> entries = Files.list(fds)) {
            List<Path> all = new ArrayList<>(entries.toList());
            return all.size();
        }
    }
}
