package com.botmaker.shared.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code capture.source} grammar: each form recognised, its argument sliced at the right character. */
class CaptureSourceKindTest {

    @Test
    void eachFormIsRecognised() {
        assertSame(CaptureSourceKind.DESKTOP, CaptureSourceKind.of("desktop"));
        assertSame(CaptureSourceKind.MONITOR, CaptureSourceKind.of("monitor:1"));
        assertSame(CaptureSourceKind.WINDOW, CaptureSourceKind.of("window:Firestone"));
        assertSame(CaptureSourceKind.EMULATOR, CaptureSourceKind.of("emulator:Waydroid"));
    }

    @Test
    void theParseIsTotal() {
        // A hand-edited or newer project file must leave the caller on its default, not throw.
        assertNull(CaptureSourceKind.of(null));
        assertNull(CaptureSourceKind.of("   "));
        assertNull(CaptureSourceKind.of("screen:0"));
        assertNull(CaptureSourceKind.of("desktop:1"));
    }

    @Test
    void caseAndSurroundingSpaceAreIgnoredAsTheSdkReaderAlwaysDid() {
        assertSame(CaptureSourceKind.DESKTOP, CaptureSourceKind.of("  DESKTOP "));
        assertEquals("Firestone", CaptureSourceKind.WINDOW.argumentOf("  Window:Firestone  "));
    }

    @Test
    void theArgumentIsSlicedAtTheEndOfThePrefix() {
        // The offsets used to be hand-counted (8 / 7 / 9) beside a second copy of the prefix.
        assertEquals("1", CaptureSourceKind.MONITOR.argumentOf("monitor:1"));
        assertEquals("MuMu Player 12", CaptureSourceKind.EMULATOR.argumentOf("emulator:MuMu Player 12"));
        for (CaptureSourceKind kind : CaptureSourceKind.values()) {
            if (kind.takesArgument()) {
                assertEquals("x", kind.argumentOf(kind.spec("x")), kind + " must read back what it writes");
            }
        }
    }

    @Test
    void anArgumentOfAnotherFormOrOfNoneIsNull() {
        assertNull(CaptureSourceKind.EMULATOR.argumentOf("window:Firestone"));
        assertNull(CaptureSourceKind.EMULATOR.argumentOf("emulator:"));
        assertNull(CaptureSourceKind.EMULATOR.argumentOf("emulator:   "));
        assertNull(CaptureSourceKind.DESKTOP.argumentOf("desktop"));
    }

    @Test
    void desktopIsTheOnlyArgumentLessForm() {
        assertEquals("desktop", CaptureSourceKind.DESKTOP.spec(null));
        for (CaptureSourceKind kind : CaptureSourceKind.values()) {
            assertEquals(kind != CaptureSourceKind.DESKTOP, kind.takesArgument());
            assertTrue(kind.matches(kind.spec("name")), kind + " must recognise the spec it builds");
        }
    }
}
