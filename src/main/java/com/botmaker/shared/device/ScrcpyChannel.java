package com.botmaker.shared.device;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.DeviceStream;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * <b>A live scrcpy session on a device: one continuous H.264 stream out, one input socket in.</b>
 *
 * <p>This is the fast path the whole phone effort is aimed at, and what it removes is not transport overhead —
 * it is two per-operation costs that no amount of faster plumbing under the ADB verbs could touch:
 *
 * <ul>
 *   <li><b>Per-frame.</b> {@code screencap} composites and copies a whole framebuffer on demand, then the host
 *       waits for all of it. Here the device encodes continuously and the newest picture is already arriving.</li>
 *   <li><b>Per-tap.</b> {@code input tap} execs {@code app_process} — a JVM start on the device, per tap.
 *       Here a tap is a 32-byte write to a process that is already holding the injection binder.</li>
 * </ul>
 *
 * <h2>How it starts</h2>
 *
 * <ol>
 *   <li>Push {@code scrcpy-server} to {@code /data/local/tmp} (see {@link ScrcpyServer} for why it is located
 *       rather than bundled).</li>
 *   <li>Run it under {@code app_process} through a {@code shell:} service that stays open for the session.</li>
 *   <li>The server listens on an abstract socket named for a random session id; connect to it twice — video
 *       first, then control, which is the order it accepts them in.</li>
 *   <li>Read the header it writes on the video socket, and <b>assert the geometry</b> against what the device
 *       says its screen is.</li>
 * </ol>
 *
 * <h2>No {@code max_size}, deliberately</h2>
 *
 * <p>{@code docs/display-pipeline.md} §3: the framebuffer, the stream and the project's reference resolution
 * must be <b>one number</b>. A {@code max_size} would put a scaler between the templates a bot matches and the
 * pixels it taps, and the failure mode is the quiet one — matching keeps working while every tap lands
 * slightly wrong. So the stream is native-resolution and the bit rate absorbs the cost.
 *
 * <h2>Unverified against a device</h2>
 *
 * <p>Nothing in this repo has yet exchanged a byte with a real scrcpy server: this machine has no scrcpy
 * installed and no phone attached. The framing below is transcribed from the protocol, and the pure parts of
 * it ({@link #readHeader}, {@link #arguments}, {@link ScrcpyControl}) are under test against synthesised
 * bytes — but "the tests pass" here means the encoder agrees with what we wrote down, not that a device
 * agrees with it. Every failure path therefore degrades to the ADB floor rather than propagating.
 */
public final class ScrcpyChannel implements AutoCloseable {

    /** Where the server is pushed. The one directory the shell user can always write and execute from. */
    private static final String REMOTE_PATH = "/data/local/tmp/botmaker-scrcpy-server.jar";

    /** The server's entry point — its own class name, unchanged since 1.x. */
    private static final String MAIN_CLASS = "com.genymobile.scrcpy.Server";

    /** How long to keep retrying the connect while the server starts up. Cold start on a phone is ~1s. */
    private static final long ACCEPT_TIMEOUT_MS = 8_000;
    private static final long ACCEPT_RETRY_MS = 100;

    /** The device name the server sends: a fixed-width, NUL-padded field. */
    private static final int DEVICE_NAME_BYTES = 64;

    /** {@code h264} as a four-character code — the codec id the server announces for our requested encoder. */
    private static final int CODEC_H264 = 0x68323634;

    /** What the stream turned out to be, read from the server rather than assumed. */
    public record Header(String deviceName, int width, int height) {}

    private final AdbDevice device;
    private final DeviceStream process;
    private final DeviceStream video;
    private final DeviceStream control;
    private final Header header;
    private final Thread videoReader;
    private final Thread logReader;

    private volatile boolean closed;

    private ScrcpyChannel(AdbDevice device, DeviceStream process, DeviceStream video, DeviceStream control,
                          Header header, Consumer<byte[]> sink) {
        this.device = device;
        this.process = process;
        this.video = video;
        this.control = control;
        this.header = header;
        this.logReader = drain(process.in(), "scrcpy-log");
        this.videoReader = pump(video.in(), sink);
    }

    /** The device's own framebuffer size, as the server reported it. The one number §3 is about. */
    public Header header() {
        return header;
    }

    /**
     * Brings up a session, or returns {@code null} if any step of it does not work out.
     *
     * <p>{@code null} rather than an exception because every caller has the same answer to every failure here —
     * use the ADB floor — and there is no step whose failure means something different to them. What went
     * wrong is worth logging at the call site, not worth a type.
     *
     * @param sink receives raw Annex-B bytes as they arrive, on this channel's own reader thread, in whatever
     *             sizes the socket delivers them. Splitting them into access units is the consumer's job
     *             (Studio has {@code session/video/AnnexB} for exactly this).
     */
    public static ScrcpyChannel open(AdbDevice device, ScrcpyServer.Located server, ScrcpyOptions options,
                                     Consumer<byte[]> sink) {
        if (device == null || server == null || sink == null) {
            return null;
        }
        if (!device.push(server.jar(), REMOTE_PATH)) {
            return null;
        }
        int scid = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        DeviceStream process = null;
        DeviceStream video = null;
        DeviceStream control = null;
        try {
            process = device.openService("shell:" + command(server.version(), options, scid));
            String socket = "localabstract:scrcpy_" + String.format(Locale.ROOT, "%08x", scid);
            video = connect(device, socket);
            if (video == null) {
                throw new IOException("the server never listened on " + socket);
            }
            Header header = readHeader(video.in());
            control = connect(device, socket);
            if (control == null) {
                throw new IOException("the control socket was refused");
            }
            return new ScrcpyChannel(device, process, video, control, header, sink);
        } catch (Exception e) {
            closeAll(control, video, process);
            return null;
        }
    }

    /**
     * The whole {@code shell:} command line. Package-visible and pure so the argument set is asserted without
     * a device — a typo in one {@code key=value} is a server that exits immediately and a socket that simply
     * never accepts, which is indistinguishable at run time from a slow phone.
     */
    static String command(ScrcpyServer.Version version, ScrcpyOptions options, int scid) {
        return "CLASSPATH=" + REMOTE_PATH + " app_process / " + MAIN_CLASS + " "
                + String.join(" ", arguments(version, options, scid));
    }

    /** The server's arguments: its version first — it rejects a mismatch — then {@code key=value} pairs. */
    static List<String> arguments(ScrcpyServer.Version version, ScrcpyOptions options, int scid) {
        List<String> args = new ArrayList<>();
        args.add(version.text());
        args.add("scid=" + String.format(Locale.ROOT, "%08x", scid));
        args.add("log_level=error");
        // The server listens and we connect, which is the only arrangement reachable without an adb forward.
        args.add("tunnel_forward=true");
        args.add("audio=false");
        args.add("control=true");
        args.add("cleanup=true");
        args.add("video_codec=h264");
        args.add("video_bit_rate=" + options.bitRate());
        args.add("max_fps=" + options.maxFps());
        // A sleeping screen still encodes — as black frames — so this is the difference between a run that
        // fails and a run that quietly stops matching. Both keys are in the server's parser from 2.x on.
        args.add("stay_awake=" + options.stayAwake());
        args.add("power_on=" + options.powerOn());
        // Note what is *not* here: max_size. See the class javadoc — a scaler here breaks the §3 invariant.
        return args;
    }

    /**
     * Reads the header the server writes before the first picture: a dummy byte (its way of letting a client
     * tell "connected" from "connect succeeded because the tunnel exists"), the device name, then the codec
     * and the size.
     *
     * <p>Package-visible and taking a plain stream so the layout is testable from synthesised bytes. It throws
     * on anything unexpected rather than guessing, because a header read wrong shifts every following byte and
     * the decoder would then fail somewhere far away from the cause.
     */
    static Header readHeader(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        data.readByte();   // dummy byte
        byte[] name = new byte[DEVICE_NAME_BYTES];
        data.readFully(name);
        int codec = data.readInt();
        if (codec != CODEC_H264) {
            throw new IOException("the server announced codec 0x" + Integer.toHexString(codec)
                    + ", not the h264 that was asked for");
        }
        int width = data.readInt();
        int height = data.readInt();
        if (width <= 0 || height <= 0) {
            throw new IOException("the server announced a " + width + "x" + height + " stream");
        }
        return new Header(trimNul(name), width, height);
    }

    /** The NUL-padded device name as a string. */
    private static String trimNul(byte[] field) {
        int end = 0;
        while (end < field.length && field[end] != 0) {
            end++;
        }
        return new String(field, 0, end, StandardCharsets.UTF_8);
    }

    /** Retries the connect until the server is listening or the budget runs out. */
    private static DeviceStream connect(AdbDevice device, String socket) {
        long deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                return device.openService(socket);
            } catch (Exception notYet) {
                try {
                    Thread.sleep(ACCEPT_RETRY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    // --- input ---

    /** A tap: press and release at one point. Silently dropped once the channel is closed or broken. */
    public void tap(int x, int y) {
        send(ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, x, y, header.width(), header.height()));
        send(ScrcpyControl.touch(ScrcpyControl.ACTION_UP, x, y, header.width(), header.height()));
    }

    /**
     * A drag, as a down, a run of moves and an up.
     *
     * <p>The moves are what make it a drag rather than a teleport: Android's gesture detectors read velocity
     * from the intermediate points, so a down-then-up at the far end is a tap in the wrong place and a single
     * huge move is a fling. The pacing is real sleeps, because the timestamps the device sees are its own.
     */
    public void drag(int x1, int y1, int x2, int y2, long durationMs) {
        int width = header.width();
        int height = header.height();
        int steps = (int) Math.max(2, Math.min(60, durationMs / 16));
        send(ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, x1, y1, width, height));
        for (int step = 1; step <= steps; step++) {
            double progress = (double) step / steps;
            int x = (int) Math.round(x1 + (x2 - x1) * progress);
            int y = (int) Math.round(y1 + (y2 - y1) * progress);
            send(ScrcpyControl.touch(ScrcpyControl.ACTION_MOVE, x, y, width, height));
            sleep(durationMs / steps);
        }
        send(ScrcpyControl.touch(ScrcpyControl.ACTION_UP, x2, y2, width, height));
    }

    /** A scroll at a point. {@code notches} is positive for up, matching the pilot's wire convention. */
    public void scroll(int x, int y, int notches) {
        send(ScrcpyControl.scroll(x, y, header.width(), header.height(), 0, notches));
    }

    /** A key by Android keycode: down then up. */
    public void key(int keyCode) {
        send(ScrcpyControl.keycode(ScrcpyControl.KEY_DOWN, keyCode, 0));
        send(ScrcpyControl.keycode(ScrcpyControl.KEY_UP, keyCode, 0));
    }

    /**
     * Writes one control message, flushed. Synchronized because the socket carries whole messages and two
     * threads interleaving halves of two gestures would desynchronise it permanently.
     */
    private synchronized void send(byte[] message) {
        if (closed) {
            return;
        }
        try {
            OutputStream out = control.out();
            out.write(message);
            out.flush();
        } catch (Exception e) {
            // A broken control socket means the session is over; the caller's next grab returns null and it
            // falls back. Failing loudly here would only throw out of a gesture handler.
            closed = true;
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- lifecycle ---

    /** Whether the session is still running. False once anything on it has failed. */
    public boolean alive() {
        return !closed;
    }

    /** Forwards everything the socket delivers to {@code sink} until it ends. */
    private Thread pump(InputStream in, Consumer<byte[]> sink) {
        return start("scrcpy-video", () -> {
            byte[] buffer = new byte[1 << 16];
            try {
                int read;
                while (!closed && (read = in.read(buffer)) > 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    sink.accept(chunk);
                }
            } catch (Exception e) {
                // EOF or a dropped connection — either way the stream is over.
            } finally {
                closed = true;
            }
        });
    }

    /**
     * Reads and discards the server's stdout/stderr. Not optional: dadb only acknowledges bytes a caller has
     * taken, so a process stream nobody reads stops being acknowledged and eventually stalls the connection
     * the video shares. The bytes are of no interest; reading them is.
     */
    private Thread drain(InputStream in, String name) {
        return start(name, () -> {
            byte[] scratch = new byte[4096];
            try {
                while (!closed && in.read(scratch) >= 0) {
                    // discarded on purpose — see above
                }
            } catch (Exception e) {
                // the process ended
            }
        });
    }

    private static Thread start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Ends the session. The server exits when its sockets close ({@code cleanup=true} makes it tidy up the
     * device side), so there is no process to kill by name — which is what keeps two Studios on one phone from
     * killing each other's session.
     */
    @Override
    public void close() {
        closed = true;
        closeAll(control, video, process);
        interrupt(videoReader);
        interrupt(logReader);
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void closeAll(AutoCloseable... streams) {
        for (AutoCloseable stream : streams) {
            if (stream == null) {
                continue;
            }
            try {
                stream.close();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
    }

    /** Never used for control, kept so a caller can name the device this channel belongs to in a log line. */
    public AdbDevice device() {
        return device;
    }
}
