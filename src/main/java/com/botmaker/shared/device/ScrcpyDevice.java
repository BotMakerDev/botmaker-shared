package com.botmaker.shared.device;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.AdbEndpoint;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * <b>The fast path, as one object</b>: a continuously-encoded screen and directly-injected input, over a
 * device that is already connected. This is what a caller uses; {@link ScrcpyChannel} and {@link ScrcpyFrames}
 * are the two halves it holds.
 *
 * <p>It deliberately mirrors the shape of the {@link AdbDevice} verbs it replaces — {@code screencap},
 * {@code tap}, {@code swipe}, {@code key} — so a consumer can hold one or the other behind its own interface
 * and the fall-back is a change of field, not a change of code. Studio's {@code EmulatorSurface} is exactly
 * that seam.
 *
 * <h2>Two consumers, one encode</h2>
 *
 * <p>The same bytes serve both things a display is for, and neither pays for the other:
 *
 * <ul>
 *   <li><b>A bot's vision</b> pulls {@link #grab()}, which is the newest decoded picture — one {@code ffmpeg}
 *       decode for the whole session rather than a device-side encode per frame.</li>
 *   <li><b>The pilot</b> can take {@link Builder#tapping(Consumer) a tap} on the raw Annex-B and put it
 *       straight on a WebSocket. That path costs the host <b>no encode and no decode at all</b> — better than
 *       the x11grab route beside it, which encodes on the host.</li>
 * </ul>
 *
 * <p>Both are optional. A session with no {@code ffmpeg} still injects input and still feeds a raw tap; it
 * just cannot answer {@link #grab()}, and {@link #canGrab()} says so.
 */
public final class ScrcpyDevice implements AutoCloseable {

    private final ScrcpyChannel channel;
    private final ScrcpyFrames frames;

    /** The ADB connection to close along with this session, or null when the caller owns it. */
    private final AdbDevice ownedTransport;

    private ScrcpyDevice(ScrcpyChannel channel, ScrcpyFrames frames, AdbDevice ownedTransport) {
        this.channel = channel;
        this.frames = frames;
        this.ownedTransport = ownedTransport;
    }

    /**
     * Starts a session on {@code device}, or returns null when the fast path is not available here — no
     * scrcpy installed, a version too old, a device that refused the push, or a handshake that did not match.
     *
     * <p>Null is the whole error contract, for the reason {@link ScrcpyChannel#open} gives: every caller
     * answers every one of those the same way, by using the ADB floor.
     *
     * <p><b>The caller keeps ownership of {@code device}</b> and must close it. Use {@link #connect} when
     * there is no reason to hold the connection separately — which is most of the time.
     */
    public static ScrcpyDevice open(AdbDevice device) {
        return builder(device).open();
    }

    /**
     * Connects to {@code endpoint} and starts a session that <b>owns that connection</b> — {@link #close()}
     * closes both. Null when either step fails, and nothing is left open on the way out.
     *
     * <p>This exists because the alternative made every caller write the same careful teardown: connect, try
     * to start, close the connection again if it didn't. Getting that wrong leaks an ADB connection per failed
     * attempt, and the retry loop a fall-back needs means "per failed attempt" is "forever".
     */
    public static ScrcpyDevice connect(AdbEndpoint endpoint) {
        AdbDevice transport;
        try {
            transport = AdbDevice.connect(endpoint);
        } catch (Exception e) {
            return null;
        }
        ScrcpyDevice session = builder(transport).owningTransport().open();
        if (session == null) {
            transport.close();
        }
        return session;
    }

    /** A session with something other than the default encoder settings, or with a raw-stream tap. */
    public static Builder builder(AdbDevice device) {
        return new Builder(device);
    }

    /** Fluent bring-up — there are three optional things to say and a four-argument factory said none of them. */
    public static final class Builder {

        private final AdbDevice device;
        private ScrcpyOptions options = ScrcpyOptions.defaults();
        private Consumer<byte[]> tap;
        private boolean decode = true;
        private boolean owned;

        private Builder(AdbDevice device) {
            this.device = device;
        }

        /** Encoder settings. Defaults to {@link ScrcpyOptions#defaults()}. */
        public Builder with(ScrcpyOptions newOptions) {
            this.options = newOptions == null ? ScrcpyOptions.defaults() : newOptions;
            return this;
        }

        /**
         * Also hand every raw Annex-B chunk to {@code sink}, on the channel's reader thread. For a consumer
         * that forwards H.264 rather than looking at pixels — see the class javadoc.
         */
        public Builder tapping(Consumer<byte[]> sink) {
            this.tap = sink;
            return this;
        }

        /** Skip the {@code ffmpeg} decode entirely, for a session that only forwards and injects. */
        public Builder withoutFrames() {
            this.decode = false;
            return this;
        }

        /** Hand the ADB connection's lifetime to the session — what {@link #connect} is built on. */
        private Builder owningTransport() {
            this.owned = true;
            return this;
        }

        /** Brings the session up, or returns null. */
        public ScrcpyDevice open() {
            if (device == null) {
                return null;
            }
            // ensure(), not locate(): this is a capture path, so it is the one place allowed to fetch the
            // server if it is missing. A bot running headless has no dialog to click, and the alternative is
            // that a published bot never gets the fast path on a machine that has never had scrcpy. Still
            // best-effort — no network leaves this null and the caller falls back to the ADB floor.
            ScrcpyServer.Located server = ScrcpyServer.ensure().orElse(null);
            if (server == null) {
                return null;
            }
            // The decoder cannot start before the header says how big a picture is, and the header does not
            // arrive until the channel is up — so the channel's sink is a holder the decoder is dropped into.
            Frames holder = new Frames();
            Consumer<byte[]> sink = chunk -> {
                holder.feed(chunk);
                if (tap != null) {
                    tap.accept(chunk);
                }
            };
            ScrcpyChannel channel = ScrcpyChannel.open(device, server, options, sink);
            if (channel == null) {
                return null;
            }
            ScrcpyFrames decoded = decode
                    ? ScrcpyFrames.start(channel.header().width(), channel.header().height())
                    : null;
            holder.set(decoded);
            return new ScrcpyDevice(channel, decoded, owned ? device : null);
        }
    }

    /**
     * A one-slot indirection so the video sink can exist before the decoder does.
     *
     * <p>The alternative — buffering the chunks that arrive in that window and replaying them — would be
     * wrong, not merely more work: those first bytes are the parameter sets and the first keyframe, and a
     * decoder started at the second keyframe simply waits for it. Dropping a few milliseconds of stream is
     * the correct behaviour; the decoder starts at the next IDR, which the encoder sends on its own.
     */
    private static final class Frames {

        private volatile ScrcpyFrames target;

        void set(ScrcpyFrames frames) {
            this.target = frames;
        }

        void feed(byte[] chunk) {
            ScrcpyFrames current = target;
            if (current != null) {
                current.feed(chunk);
            }
        }
    }

    /** The device's framebuffer size, as the server announced it. The one number {@code §3} is about. */
    public Dimension size() {
        return new Dimension(channel.header().width(), channel.header().height());
    }

    /** The device name the server reported — {@code ro.product.model}, effectively. */
    public String deviceName() {
        return channel.header().deviceName();
    }

    /** Whether frames can be produced at all — false when this session was opened without a decoder. */
    public boolean canGrab() {
        return frames != null;
    }

    /**
     * The newest decoded picture, or null before the first one arrives or once the session has ended.
     *
     * <p>Null-on-failure rather than throwing, matching {@code AdbDevice.screencap()} and every other
     * best-effort grab in this stack — a frame loop must not be an exception handler.
     */
    public BufferedImage grab() {
        return frames == null ? null : frames.newest();
    }

    /** Whether the session is still usable. Once false it stays false; open a new one. */
    public boolean alive() {
        return channel.alive() && (frames == null || frames.alive());
    }

    /** A tap in the device's own framebuffer coordinates — the same space {@link #grab()} returns. */
    public void tap(int x, int y) {
        channel.tap(x, y);
    }

    /** A drag in framebuffer coordinates, paced over {@code durationMs}. */
    public void swipe(int x1, int y1, int x2, int y2, long durationMs) {
        channel.drag(x1, y1, x2, y2, durationMs);
    }

    /** A scroll centred on a point; positive is up, matching the pilot's wire convention. */
    public void scroll(int x, int y, int notches) {
        channel.scroll(x, y, notches);
    }

    /** A key by Android keycode. */
    public void key(int keyCode) {
        channel.key(keyCode);
    }

    @Override
    public void close() {
        if (frames != null) {
            frames.close();
        }
        channel.close();
        if (ownedTransport != null) {
            ownedTransport.close();
        }
    }
}
