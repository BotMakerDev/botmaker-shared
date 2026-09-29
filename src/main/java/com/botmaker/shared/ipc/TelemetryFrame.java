package com.botmaker.shared.ipc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Optional;

/**
 * Length-prefixed binary framing for {@link TelemetryEvent}s. It stays binary for the wire, not for a dependency
 * ({@code botmaker-shared} declares Jackson for its GitHub client): every published SDK writes these frames, and a
 * host must keep reading them ({@code docs/refactor/40-run-trace.md}). Wire layout of one frame:
 *
 * <pre>
 *   int32  payloadLength (big-endian, via DataOutputStream)
 *   byte   protocolVersion
 *   byte   typeTag  (1=Match, 2=Click, 3=Region, 4=Swipe, 5=Log)
 *   ...    type-specific fields, encoded field-by-field
 * </pre>
 *
 * The 1-byte version lets shared/sdk/studio evolve independently: a reader rejects a frame whose version it
 * does not understand rather than misreading it.
 */
public final class TelemetryFrame {

    /** Bumped when the on-wire encoding changes incompatibly. v2 added a trailing {@code line} per event. */
    public static final int PROTOCOL_VERSION = 2;

    /** Guards a decoder against absurd length prefixes (a stray/misaligned stream). */
    static final int MAX_FRAME_BYTES = 1 << 20;

    private static final int TYPE_MATCH = 1;
    private static final int TYPE_CLICK = 2;
    private static final int TYPE_REGION = 3;
    /**
     * Added after v2 shipped, deliberately <em>without</em> bumping the version. A new tag is the one kind of
     * change the framing already survives in both directions: an older reader hits the {@code default} branch
     * and raises a {@link FrameFormatException}, which skips that frame and leaves the stream aligned, while an
     * older writer simply never emits it. Bumping the version instead would reject every frame from an
     * older-SDK bot, including the three kinds that reader understands perfectly.
     */
    private static final int TYPE_SWIPE = 4;
    /**
     * A debug line ({@link TelemetryEvent.Log}), added the same way as {@link #TYPE_SWIPE}. Its text fits
     * {@code writeUTF}'s 64 KiB because {@link TelemetryEvent.Log#MAX_TEXT} characters are at most three bytes
     * each.
     */
    private static final int TYPE_LOG = 5;

    private TelemetryFrame() {}

    /** Encodes and writes one framed event, then flushes. */
    public static void write(DataOutputStream out, TelemetryEvent event) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(buffer);
        p.writeByte(PROTOCOL_VERSION);
        switch (event) {
            case TelemetryEvent.Match m -> {
                p.writeByte(TYPE_MATCH);
                writeTarget(p, m.target());
                writeNullableRect(p, m.region());
                writeNullableRect(p, m.rect());
                p.writeDouble(m.confidence());
                p.writeBoolean(m.found());
                p.writeInt(m.line());
            }
            case TelemetryEvent.Click c -> {
                p.writeByte(TYPE_CLICK);
                writeTarget(p, c.target());
                p.writeInt(c.x());
                p.writeInt(c.y());
                p.writeInt(c.button());
                p.writeInt(c.line());
            }
            case TelemetryEvent.Region r -> {
                p.writeByte(TYPE_REGION);
                writeTarget(p, r.target());
                writeNullableRect(p, r.rect());
                p.writeInt(r.line());
            }
            case TelemetryEvent.Swipe s -> {
                p.writeByte(TYPE_SWIPE);
                writeTarget(p, s.target());
                p.writeInt(s.x1());
                p.writeInt(s.y1());
                p.writeInt(s.x2());
                p.writeInt(s.y2());
                p.writeLong(s.durationMs());
                p.writeInt(s.line());
            }
            case TelemetryEvent.Log l -> {
                p.writeByte(TYPE_LOG);
                p.writeUTF(l.level());
                p.writeUTF(l.source());
                p.writeUTF(l.text());
                p.writeInt(l.count());
                p.writeLong(l.atMillis());
                writeNullableRect(p, l.rect());
                p.writeUTF(l.writerClass());
                p.writeUTF(l.writerMethod());
                p.writeUTF(l.className());
                p.writeInt(l.line());
            }
        }
        byte[] payload = buffer.toByteArray();
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /**
     * A frame whose length prefix read fine but whose <em>payload</em> couldn't be decoded — e.g. a frame
     * from an older-SDK bot speaking a different {@link #PROTOCOL_VERSION}, or an unknown type tag. Because
     * the full payload was already consumed from the stream, the connection stays byte-aligned: a reader can
     * skip this one frame and keep going rather than tearing down the whole channel. Distinct from a plain
     * {@link IOException}/{@link EOFException} out of {@link #read}, which means the stream itself is gone.
     */
    public static final class FrameFormatException extends IOException {
        public FrameFormatException(String message) {
            super(message);
        }
    }

    /**
     * Reads and decodes one framed event. Throws {@link EOFException}/{@link IOException} at a clean stream
     * end or a corrupt length prefix (fatal — the stream is gone/desynced), or {@link FrameFormatException}
     * when only the payload is undecodable (recoverable — framing is still aligned to the next frame).
     */
    public static TelemetryEvent read(DataInputStream in) throws IOException {
        return decode(readFrame(in));
    }

    /**
     * Reads one whole frame, length prefix included, without decoding it: the bytes {@link #write} wrote, which
     * {@link #decode} reads back. For a host that relays frames it does not read — what a match or a click means
     * is the runtime's vocabulary, and the host passes it on as bytes ({@code docs/refactor/40-run-trace.md}).
     * Fails like {@link #read} does when the stream itself is gone; never on the payload.
     */
    public static byte[] readFrame(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Bad telemetry frame length: " + length);
        }
        byte[] frame = new byte[4 + length];
        frame[0] = (byte) (length >>> 24);
        frame[1] = (byte) (length >>> 16);
        frame[2] = (byte) (length >>> 8);
        frame[3] = (byte) length;
        int read = in.readNBytes(frame, 4, length);
        if (read < length) throw new EOFException("Truncated telemetry frame");
        return frame;
    }

    /**
     * Whether {@code frame} (as {@link #readFrame} returned it) is a debug line, read off its type tag alone.
     * A frame of a version this reader does not know is not.
     */
    public static boolean isLog(byte[] frame) {
        return frame != null && frame.length > 5
                && (frame[4] & 0xFF) == PROTOCOL_VERSION && (frame[5] & 0xFF) == TYPE_LOG;
    }

    /**
     * The debug line {@code frame} carries, or empty when it carries something else or cannot be read. Decodes
     * nothing but a {@link TelemetryEvent.Log}, so a host that shows the trace names no other event.
     */
    public static Optional<TelemetryEvent.Log> log(byte[] frame) {
        if (!isLog(frame)) return Optional.empty();
        try {
            return Optional.of((TelemetryEvent.Log) decode(frame));
        } catch (FrameFormatException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Decodes one whole frame, as {@link #readFrame} returned it. Every failure is a {@link FrameFormatException}:
     * the bytes are all here, so nothing about the stream they came from is in doubt.
     */
    public static TelemetryEvent decode(byte[] frame) throws FrameFormatException {
        if (frame == null || frame.length < 4) throw new FrameFormatException("Truncated telemetry frame");
        int length = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16) | ((frame[2] & 0xFF) << 8)
                | (frame[3] & 0xFF);
        if (length != frame.length - 4) {
            throw new FrameFormatException("Telemetry frame length " + length + " does not match its payload");
        }

        // Any failure here (version skew, unknown tag, short payload) is recoverable — surface it as
        // FrameFormatException.
        try {
            DataInputStream p = new DataInputStream(new ByteArrayInputStream(frame, 4, length));
            int version = p.readUnsignedByte();
            if (version != PROTOCOL_VERSION) {
                throw new IOException("Unsupported telemetry protocol version: " + version);
            }
            int type = p.readUnsignedByte();
            return switch (type) {
                case TYPE_MATCH -> new TelemetryEvent.Match(
                        readTarget(p), readNullableRect(p), readNullableRect(p), p.readDouble(), p.readBoolean(), p.readInt());
                case TYPE_CLICK -> new TelemetryEvent.Click(
                        readTarget(p), p.readInt(), p.readInt(), p.readInt(), p.readInt());
                case TYPE_REGION -> new TelemetryEvent.Region(
                        readTarget(p), readNullableRect(p), p.readInt());
                case TYPE_SWIPE -> new TelemetryEvent.Swipe(
                        readTarget(p), p.readInt(), p.readInt(), p.readInt(), p.readInt(),
                        p.readLong(), p.readInt());
                case TYPE_LOG -> new TelemetryEvent.Log(
                        p.readUTF(), p.readUTF(), p.readUTF(), p.readInt(), p.readLong(),
                        readNullableRect(p), p.readUTF(), p.readUTF(), p.readUTF(), p.readInt());
                default -> throw new IOException("Unknown telemetry type tag: " + type);
            };
        } catch (IOException decodeError) {
            throw new FrameFormatException(decodeError.getMessage());
        }
    }

    private static void writeTarget(DataOutputStream p, TelemetryEvent.Target t) throws IOException {
        writeNullableString(p, t.title());
        p.writeInt(t.x());
        p.writeInt(t.y());
        p.writeInt(t.width());
        p.writeInt(t.height());
    }

    private static TelemetryEvent.Target readTarget(DataInputStream p) throws IOException {
        return new TelemetryEvent.Target(
                readNullableString(p), p.readInt(), p.readInt(), p.readInt(), p.readInt());
    }

    private static void writeNullableRect(DataOutputStream p, TelemetryEvent.Rect r) throws IOException {
        if (r == null) {
            p.writeBoolean(false);
            return;
        }
        p.writeBoolean(true);
        p.writeInt(r.x());
        p.writeInt(r.y());
        p.writeInt(r.width());
        p.writeInt(r.height());
    }

    private static TelemetryEvent.Rect readNullableRect(DataInputStream p) throws IOException {
        if (!p.readBoolean()) return null;
        return new TelemetryEvent.Rect(p.readInt(), p.readInt(), p.readInt(), p.readInt());
    }

    private static void writeNullableString(DataOutputStream p, String s) throws IOException {
        if (s == null) {
            p.writeBoolean(false);
            return;
        }
        p.writeBoolean(true);
        p.writeUTF(s);
    }

    private static String readNullableString(DataInputStream p) throws IOException {
        if (!p.readBoolean()) return null;
        return p.readUTF();
    }
}
