package com.botmaker.shared.ipc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryFrameTest {

    private static final TelemetryEvent.Target WINDOW =
            new TelemetryEvent.Target("Notepad", 100, 200, 800, 600);
    private static final TelemetryEvent.Target SCREEN =
            new TelemetryEvent.Target(null, 0, 0, 1920, 1080);

    private static TelemetryEvent roundTrip(TelemetryEvent event) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TelemetryFrame.write(new DataOutputStream(bos), event);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        return TelemetryFrame.read(in);
    }

    @Test
    void matchFoundRoundTrips() throws Exception {
        TelemetryEvent event = new TelemetryEvent.Match(
                WINDOW,
                new TelemetryEvent.Rect(10, 20, 30, 40),
                new TelemetryEvent.Rect(50, 60, 24, 24),
                0.93, true);
        assertEquals(event, roundTrip(event));
    }

    @Test
    void matchNotFoundWithNullRectsRoundTrips() throws Exception {
        TelemetryEvent event = new TelemetryEvent.Match(SCREEN, null, null, 0.0, false);
        assertEquals(event, roundTrip(event));
    }

    @Test
    void clickRoundTrips() throws Exception {
        TelemetryEvent event = new TelemetryEvent.Click(WINDOW, 640, 480, 3);
        assertEquals(event, roundTrip(event));
    }

    @Test
    void regionRoundTrips() throws Exception {
        TelemetryEvent event = new TelemetryEvent.Region(
                SCREEN, new TelemetryEvent.Rect(0, 0, 100, 100));
        assertEquals(event, roundTrip(event));
    }

    @Test
    void swipeCarriesBothEndsAndHowLongItTook() throws Exception {
        // The duration is a long on the wire because it is a duration, not because anyone will swipe for
        // 25 days — an int would still be right, and would be the sort of thing that quietly stops being.
        TelemetryEvent event = new TelemetryEvent.Swipe(WINDOW, 200, 900, 200, 300, 450);
        assertEquals(event, roundTrip(event));
    }

    @Test
    void anUnknownTypeTagSkipsOneFrameRatherThanKillingTheStream() throws Exception {
        // Why Swipe could be added without bumping PROTOCOL_VERSION: a reader that predates a tag consumes
        // the whole payload before it fails, so the next frame — one it does understand — still reads.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        byte[] unknown = {TelemetryFrame.PROTOCOL_VERSION, 99, 1, 2, 3};
        out.writeInt(unknown.length);
        out.write(unknown);
        TelemetryEvent next = new TelemetryEvent.Click(WINDOW, 7, 8, 1);
        TelemetryFrame.write(out, next);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        assertThrows(TelemetryFrame.FrameFormatException.class, () -> TelemetryFrame.read(in));
        assertEquals(next, TelemetryFrame.read(in), "the stream must still be aligned to the next frame");
    }

    @Test
    void multipleFramesReadBackInOrder() throws Exception {
        TelemetryEvent a = new TelemetryEvent.Click(WINDOW, 1, 2, 1);
        TelemetryEvent b = new TelemetryEvent.Region(WINDOW, new TelemetryEvent.Rect(3, 4, 5, 6));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        TelemetryFrame.write(out, a);
        TelemetryFrame.write(out, b);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(a, TelemetryFrame.read(in));
        assertEquals(b, TelemetryFrame.read(in));
        assertThrows(EOFException.class, () -> TelemetryFrame.read(in));
    }
}
