package com.botmaker.shared.device;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handshake and the command line — the two parts of bring-up that fail <em>silently</em> on a real device
 * and so are worth pinning here.
 *
 * <p>Both failures look identical at run time: a socket that never accepts. A wrong version string, a
 * misspelled argument and an unstarted server are indistinguishable from a slow phone until the timeout
 * expires. So the argument set and the header layout are asserted against synthesised bytes, where a mismatch
 * is a failed assertion instead of an eight-second wait and a fall-back nobody notices.
 */
class ScrcpyChannelTest {

    private static final ScrcpyServer.Version V2_7 = new ScrcpyServer.Version(2, 7, "2.7");
    private static final int SCID = 0x0BADF00D;

    /** The version goes first and is passed through verbatim — the server compares it to its own, exactly. */
    @Test
    void theVersionLeadsTheArgumentsAndIsVerbatim() {
        List<String> args = ScrcpyChannel.arguments(V2_7, ScrcpyOptions.defaults(), SCID);

        assertEquals("2.7", args.get(0));
    }

    /**
     * A {@code 2.7.1} install must be announced as {@code 2.7.1}, not as the {@code major.minor} this code
     * compares on. The server does a string equality against its own version and would reject the rounding.
     */
    @Test
    void aPatchVersionIsAnnouncedInFull() {
        ScrcpyServer.Version patch = ScrcpyServer.parse("scrcpy 2.7.1");

        assertEquals("2.7.1", ScrcpyChannel.arguments(patch, ScrcpyOptions.defaults(), SCID).get(0));
        assertEquals(2, patch.major());
        assertEquals(7, patch.minor());
    }

    /**
     * <b>The §3 sizing invariant, asserted at the one place it could be broken.</b> A {@code max_size} would
     * put a scaler between the templates a bot matches and the pixels it taps — and the failure is the quiet
     * kind, where matching keeps succeeding and every tap lands slightly wrong.
     */
    @Test
    void noMaxSizeIsEverRequested() {
        for (ScrcpyOptions options : List.of(ScrcpyOptions.defaults(), new ScrcpyOptions(4_000_000, 30))) {
            String command = ScrcpyChannel.command(V2_7, options, SCID);
            assertFalse(command.contains("max_size"),
                    "a scaler here breaks the one-number invariant: " + command);
        }
    }

    /** The scid names the socket, so the two must agree or the client connects to nothing. */
    @Test
    void theScidIsEightHexDigits() {
        assertTrue(ScrcpyChannel.arguments(V2_7, ScrcpyOptions.defaults(), SCID).contains("scid=0badf00d"),
                "the socket name is scrcpy_<scid>, zero-padded and lower-case");
    }

    /** Everything the channel depends on being on, stated once so a silent default cannot change it. */
    @Test
    void theSessionAsksForVideoAndControlAndNoAudio() {
        List<String> args = ScrcpyChannel.arguments(V2_7, ScrcpyOptions.defaults(), SCID);

        assertTrue(args.contains("control=true"), "no control socket means no input injection");
        assertTrue(args.contains("audio=false"), "an unread audio socket would stall the connection");
        assertTrue(args.contains("tunnel_forward=true"), "the server listens and we connect");
        assertTrue(args.contains("video_codec=h264"));
    }

    @Test
    void theOptionsReachTheCommandLine() {
        List<String> args = ScrcpyChannel.arguments(V2_7, new ScrcpyOptions(8_000_000, 30), SCID);

        assertTrue(args.contains("video_bit_rate=8000000"));
        assertTrue(args.contains("max_fps=30"));
    }

    /** The command runs the pushed jar through app_process — the trick that needs no installed app. */
    @Test
    void theCommandRunsTheServerUnderAppProcess() {
        String command = ScrcpyChannel.command(V2_7, ScrcpyOptions.defaults(), SCID);

        assertTrue(command.startsWith("CLASSPATH=/data/local/tmp/"), command);
        assertTrue(command.contains("app_process / com.genymobile.scrcpy.Server"), command);
    }

    // --- the handshake ---

    @Test
    void theHeaderYieldsTheNameAndTheSize() throws IOException {
        ScrcpyChannel.Header header = ScrcpyChannel.readHeader(
                new ByteArrayInputStream(header("Pixel 7", 0x68323634, 1080, 2400)));

        assertEquals("Pixel 7", header.deviceName());
        assertEquals(1080, header.width());
        assertEquals(2400, header.height());
    }

    /**
     * A codec other than the h264 that was asked for must throw rather than be accepted: the decoder is
     * started for h264 and would produce nothing, which reads as a phone that is simply slow.
     */
    @Test
    void anUnexpectedCodecIsRefused() {
        assertThrows(IOException.class, () -> ScrcpyChannel.readHeader(
                new ByteArrayInputStream(header("Pixel 7", 0x68323635, 1080, 2400))),
                "h265 is not what the decoder was started for");
    }

    /** A zero-sized stream would make the decoder read zero-byte frames forever. */
    @Test
    void anEmptySizeIsRefused() {
        assertThrows(IOException.class, () -> ScrcpyChannel.readHeader(
                new ByteArrayInputStream(header("Pixel 7", 0x68323634, 0, 0))));
    }

    /**
     * A header cut short must throw, not return a partly-filled one. Every following byte is offset by the
     * shortfall, so a decoder handed the rest fails somewhere far away from the actual cause.
     */
    @Test
    void aTruncatedHeaderThrowsRatherThanGuessing() {
        byte[] complete = header("Pixel 7", 0x68323634, 1080, 2400);
        for (int length = 0; length < complete.length; length++) {
            byte[] partial = new byte[length];
            System.arraycopy(complete, 0, partial, 0, length);
            assertThrows(IOException.class,
                    () -> ScrcpyChannel.readHeader(new ByteArrayInputStream(partial)),
                    "a " + length + "-byte header must not read as complete");
        }
    }

    /** The device name is a fixed 64-byte NUL-padded field; the padding must not reach the string. */
    @Test
    void theDeviceNameLosesItsPadding() throws IOException {
        assertEquals("SM_G981B", ScrcpyChannel.readHeader(
                new ByteArrayInputStream(header("SM_G981B", 0x68323634, 1440, 3200))).deviceName());
    }

    /** One dummy byte, 64 bytes of name, then codec, width, height — all big-endian. */
    private static byte[] header(String name, int codec, int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 64 + 12);
        buffer.put((byte) 0);
        byte[] field = new byte[64];
        byte[] raw = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, field, 0, Math.min(raw.length, field.length));
        buffer.put(field);
        buffer.putInt(codec);
        buffer.putInt(width);
        buffer.putInt(height);
        return buffer.array();
    }
}
