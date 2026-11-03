package com.botmaker.shared;

import com.botmaker.shared.ipc.TelemetryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run's file keeps a quiet run's last debug lines and writes them just before a problem; the live trace never
 * hears them. With debugging on, every line is written as it comes.
 */
class DiagRingTest {

    private final List<TelemetryEvent.Log> live = new ArrayList<>();
    private final List<TelemetryEvent.Log> written = new ArrayList<>();

    @AfterEach
    void restore() {
        Diag.setSink(null);
        Diag.setFileSink(null, null);
        Diag.set(true);
    }

    @Test
    void offTheLastFiftyAreWrittenBeforeAWarningAndNotShownLive() {
        Diag.set(false);
        Diag.setSink(live::add);
        Diag.setFileSink(l -> l.at("com.example.Bot", 7), written::add);

        for (int i = 0; i < 60; i++) Diag.log("Vision", "call " + i, 1, null);
        assertTrue(written.isEmpty(), "kept, not written, until a problem");
        Diag.warn("Screens", "stuck");

        assertEquals(Diag.KEPT_LINES + 1, written.size());
        assertEquals("call 10", written.getFirst().text());
        assertEquals("stuck", written.getLast().text());
        assertEquals("com.example.Bot", written.getFirst().className());
        assertEquals(7, written.getFirst().line());
        assertEquals(List.of("stuck"), live.stream().map(TelemetryEvent.Log::text).toList());

        Diag.error("Screens", "again");
        assertEquals(Diag.KEPT_LINES + 2, written.size(), "the kept lines are written once");
    }

    @Test
    void offAnInfoLineComesAfterTheKeptLinesBeforeIt() {
        Diag.set(false);
        Diag.setFileSink(null, written::add);

        Diag.log("Vision", "d1", 1, null);
        Diag.info("Setup", "i");
        Diag.log("Vision", "d2", 1, null);
        Diag.warn("Screens", "w");

        assertEquals(List.of("d1", "i", "d2", "w"), written.stream().map(TelemetryEvent.Log::text).toList());
    }

    @Test
    void onEveryLineIsWrittenAsItComes() {
        Diag.set(true);
        Diag.setFileSink(null, written::add);

        Diag.log("Vision", "a", 1, null);
        Diag.info("Setup", "b");

        assertEquals(List.of("a", "b"), written.stream().map(TelemetryEvent.Log::text).toList());
        assertEquals(TelemetryEvent.Log.DEBUG, written.getFirst().level());
    }

    @Test
    void offWithoutAFileNothingIsRecorded() {
        Diag.set(false);
        assertTrue(!Diag.isRecorded());
        Diag.setFileSink(null, written::add);
        assertTrue(Diag.isRecorded());
    }
}
