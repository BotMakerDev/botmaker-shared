package com.botmaker.shared;

import com.botmaker.shared.ipc.TelemetryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each printed diagnostic is also a trace line: its level from which stream it went to, its source from the
 * leading {@code [Name]}, and nothing at all while diagnostics are off. The run property decides over the default.
 */
class DiagTest {

    private final List<TelemetryEvent.Log> lines = new ArrayList<>();

    @AfterEach
    void restore() {
        Diag.setSink(null);
        Diag.set(true);
        System.clearProperty(Diag.RUN_PROPERTY);
    }

    @Test
    void aPrintedLineIsTracedWithItsSourceAndLevel() {
        Diag.set(true);
        Diag.setSink(lines::add);

        Diag.log("[Vision] find ore → (10,20)", 3, new TelemetryEvent.Rect(1, 2, 3, 4));
        Diag.error("[Game] could not launch");
        Diag.log("no bracket here");

        assertEquals(3, lines.size());
        TelemetryEvent.Log find = lines.getFirst();
        assertEquals(TelemetryEvent.Log.DEBUG, find.level());
        assertEquals("Vision", find.source());
        assertEquals("find ore → (10,20)", find.text());
        assertEquals(3, find.count());
        assertEquals(new TelemetryEvent.Rect(1, 2, 3, 4), find.rect());
        assertEquals(TelemetryEvent.Log.ERROR, lines.get(1).level());
        assertEquals("Game", lines.get(1).source());
        assertEquals("", lines.get(2).source());
        assertEquals("no bracket here", lines.get(2).text());
    }

    /** The trace names the class and method that wrote a line, so a host can hide one method's lines. */
    @Test
    void aTracedLineNamesItsWriterFoundOrGiven() {
        Diag.set(true);
        Diag.setSink(lines::add);

        Diag.log("found on the stack");
        Diag.log(new Diag.Origin("Mouse", "com.example.Mouse", "click"), "given", 1, null);

        assertEquals(DiagTest.class.getName(), lines.get(0).writerClass());
        assertEquals("aTracedLineNamesItsWriterFoundOrGiven", lines.get(0).writerMethod());
        assertEquals("com.example.Mouse", lines.get(1).writerClass());
        assertEquals("click", lines.get(1).writerMethod());
        assertEquals("Mouse", lines.get(1).source());
    }

    @Test
    void aGivenSourceIsPrintedAndTracedButAnExplicitPrefixWins() {
        Diag.set(true);
        Diag.setSink(lines::add);
        PrintStream real = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            Diag.log("Mouse", "click (1,2)", 1, null);
            Diag.log("Settings", "[Input] real device input active", 1, null);
            Diag.log(" ", "no source", 1, null);
        } finally {
            System.setOut(real);
        }

        assertEquals(List.of("[Mouse] click (1,2)", "[Input] real device input active", "no source"),
                printed.toString(StandardCharsets.UTF_8).lines().toList());
        assertEquals(List.of("Mouse", "Input", ""), lines.stream().map(TelemetryEvent.Log::source).toList());
        assertEquals("real device input active", lines.get(1).text());
    }

    @Test
    void anErrorWithAThrowableCarriesItsStack() {
        Diag.set(true);
        Diag.setSink(lines::add);

        Diag.error("[Vision] error reading text", new IllegalStateException("boom"));

        assertTrue(lines.getFirst().text().contains("IllegalStateException: boom"), lines.getFirst().text());
    }

    @Test
    void aQuietRunTracesNothingAndABrokenSinkIsHarmless() {
        Diag.setSink(lines::add);
        Diag.set(false);
        Diag.log("[Vision] hidden");
        assertTrue(lines.isEmpty());

        Diag.set(true);
        Diag.setSink(line -> {
            throw new IllegalStateException("sink down");
        });
        Diag.log("[Vision] still printed");
    }

    @Test
    void onlyALeadingShortBracketIsASource() {
        assertEquals("Bot", Diag.sourceOf("[Bot] goHome"));
        assertEquals("", Diag.sourceOf("goHome [Bot]"));
        assertEquals("", Diag.sourceOf("[] empty"));
        assertEquals("", Diag.sourceOf("[ padded ] name"));
        assertEquals("", Diag.sourceOf("[" + "x".repeat(40) + "] too long"));
    }

    @Test
    void theRunPropertyIsReadAsTrueFalseOrUnset() {
        assertEquals(Optional.empty(), Diag.runOverride());
        System.setProperty(Diag.RUN_PROPERTY, " FALSE ");
        assertEquals(Optional.of(false), Diag.runOverride());
        System.setProperty(Diag.RUN_PROPERTY, "true");
        assertEquals(Optional.of(true), Diag.runOverride());
        System.setProperty(Diag.RUN_PROPERTY, "maybe");
        assertEquals(Optional.empty(), Diag.runOverride());
    }
}
