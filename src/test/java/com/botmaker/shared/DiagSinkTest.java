package com.botmaker.shared;

import com.botmaker.shared.ipc.TelemetryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The sink hears every line, a quiet run's debug lines too, so the host keeps the last ones for its log. */
class DiagSinkTest {

    private final List<TelemetryEvent.Log> heard = new ArrayList<>();

    @AfterEach
    void restore() {
        Diag.setSink(null);
        Diag.set(true);
    }

    @Test
    void offTheSinkStillHearsDebugLines() {
        Diag.set(false);
        Diag.setSink(heard::add);

        Diag.log("Vision", "d1", 1, null);
        Diag.warn("Screens", "w");

        assertEquals(List.of("d1", "w"), heard.stream().map(TelemetryEvent.Log::text).toList());
        assertEquals(TelemetryEvent.Log.DEBUG, heard.getFirst().level());
        assertEquals("", heard.getFirst().writerClass(), "an unprinted line walks no stack");
        assertEquals(DiagSinkTest.class.getName(), heard.getLast().writerClass());
    }
}
