package com.botmaker.shared.ipc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Length-prefixed binary framing for {@link TelemetryEvent}s — dependency-free (no JSON/Jackson), keeping
 * {@code botmaker-shared} JNA-only. Wire layout of one frame:
 *
 * <pre>
 *   int32  payloadLength (big-endian, via DataOutputStream)
 *   byte   protocolVersion
 *   byte   typeTag  (1=Match, 2=Click, 3=Region, 4=Swipe)
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
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Bad telemetry frame length: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) throw new EOFException("Truncated telemetry frame");

        // The socket stream is now aligned to the next frame; decode the in-memory payload. Any failure here
        // (version skew, unknown tag, short payload) is recoverable — surface it as FrameFormatException.
        try {
            DataInputStream p = new DataInputStream(new ByteArrayInputStream(payload));
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
