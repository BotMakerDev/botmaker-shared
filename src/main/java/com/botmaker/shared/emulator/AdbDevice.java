package com.botmaker.shared.emulator;

import dadb.AdbStream;
import dadb.Dadb;
import dadb.adbserver.AdbServer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * One live ADB connection to an emulator's {@code adbd} port, spoken via <b>dadb</b> (pure-JVM ADB — no
 * {@code adb.exe} or ADB server to ship). This is the whole native side of the emulator stack: a screen
 * grab and the input verbs a bot needs, expressed as ADB shell commands.
 *
 * <p>Lives in <b>shared</b> so both consumers can reach it: the SDK's {@code api.emulator.Emulator} wraps it
 * as a {@code CaptureSource} at runtime, and a Studio capture picker can screen-grab an emulator at edit time
 * (the same way it uses the shared window {@code NativeController}).
 *
 * <p>dadb manages the ADB RSA auth key itself (reads/creates {@code ~/.android/adbkey}), so there is no key
 * lifecycle to own here. Most desktop emulators run insecure {@code adbd} and accept the connection without a
 * device-side prompt.
 *
 * <p>Screen capture goes through the {@code exec:} ADB service, which pipes a command's raw stdout with no
 * PTY newline translation (the legacy {@code shell:} service corrupts binary). {@link #screencap()} picks
 * between a raw framebuffer and a device-encoded PNG based on whether the bytes cross a wire — see
 * {@link AdbEndpoint#local()}.
 *
 * <p><b>This is the floor, not the fast path.</b> Both capture paths still ask the device for a discrete
 * frame on demand, and the input verbs still exec {@code app_process} per tap, which is the real ceiling here
 * and is not reachable from the transport. A continuously-streaming channel with direct input injection is
 * what removes it; see the ROADMAP.
 */
public final class AdbDevice implements AutoCloseable {

    private final AdbEndpoint endpoint;
    private final Dadb dadb;

    /** The held shell — opened on first use, dropped and re-opened when it dies. See {@link AdbShellSession}. */
    private AdbShellSession session;

    private AdbDevice(AdbEndpoint endpoint, Dadb dadb) {
        this.endpoint = endpoint;
        this.dadb = dadb;
    }

    /**
     * Opens a connection to {@code host:port} (typically {@code 127.0.0.1:<instance adb port>}).
     *
     * @throws RuntimeException if the connection can't be established (emulator not running, ADB disabled in
     *                          the emulator's settings, or the port is wrong)
     */
    public static AdbDevice connect(String host, int port) {
        return connect(new AdbEndpoint.Tcp(host, port));
    }

    /**
     * Opens a connection to wherever {@code endpoint} says {@code adbd} is.
     *
     * <p>The two variants take genuinely different routes, which is the whole reason
     * {@link AdbEndpoint} is a closed set: a {@link AdbEndpoint.Tcp} is dialled by dadb itself — no binary, no
     * server, our own RSA auth — while a {@link AdbEndpoint.Server} is proxied through the host's adb server,
     * the only thing that can reach a phone over a USB cable.
     *
     * @throws RuntimeException if the connection can't be established
     */
    public static AdbDevice connect(AdbEndpoint endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        try {
            Dadb dadb = switch (endpoint) {
                case AdbEndpoint.Tcp tcp -> Dadb.create(tcp.host(), tcp.port());
                case AdbEndpoint.Server server ->
                        AdbServer.createDadb("localhost", AdbTools.DEFAULT_PORT,
                                "host:transport:" + server.serial());
            };
            return new AdbDevice(endpoint, dadb);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to ADB at " + endpoint.label() + ". "
                    + (endpoint instanceof AdbEndpoint.Server
                            ? "Is the device still connected and authorized? "
                            : "Is it running with ADB enabled? ")
                    + e.getMessage(), e);
        }
    }

    /** {@code host:port} or the device serial, for logging / identity. */
    public String endpoint() {
        return endpoint.label();
    }

    /**
     * A screen grab of the device's framebuffer as a {@link BufferedImage}, or {@code null} on failure.
     *
     * <p>Takes the raw path or the PNG path depending on {@link AdbEndpoint#local()}, which is where the
     * reasoning lives: raw skips a device-side encode but sends ten times the bytes, so it is the faster
     * choice on loopback and the slower one over a cable or a radio. Either way the frame that comes back is
     * the same frame — raw is lossless and so is PNG — so this is a latency decision only, with no bearing on
     * what a bot matches against.
     *
     * <p>Raw also falls back to PNG when the payload does not parse as a framebuffer, so an unexpected device
     * degrades to the path that has always worked rather than to a black screen.
     */
    public BufferedImage screencap() {
        if (endpoint.local()) {
            BufferedImage raw = screencapRaw();
            if (raw != null) {
                return raw;
            }
        }
        return screencapPng();
    }

    /**
     * {@code exec:screencap} with no {@code -p}: the framebuffer and its header, with no encode on the device.
     * {@code null} when the payload is not a framebuffer we can lay out — see {@link RawFramebuffer} for how
     * that is decided, and why the check is strong enough to fall back on.
     */
    public BufferedImage screencapRaw() {
        try (AdbStream stream = dadb.open("exec:screencap")) {
            return RawFramebuffer.decode(stream.getSource().readByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code exec:screencap -p} — the device deflates the frame before sending it. Slower to produce and much
     * smaller on the wire, which is the trade {@link #screencap()} makes on everything but loopback.
     *
     * <p>The {@code exec:} service pipes raw stdout with no PTY newline translation, so the PNG bytes arrive
     * intact; the legacy {@code shell:} service corrupts binary.
     */
    public BufferedImage screencapPng() {
        try (AdbStream stream = dadb.open("exec:screencap -p")) {
            byte[] png = stream.getSource().readByteArray();
            if (png.length == 0) {
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new UncheckedIOException("screencap failed on " + endpoint(), e);
        } catch (Exception e) {
            throw new RuntimeException("screencap failed on " + endpoint() + ": " + e.getMessage(), e);
        }
    }

    /** {@code input tap x y} — a single tap in the emulator's own pixel space. */
    public void tap(int x, int y) {
        shell("input tap " + x + " " + y);
    }

    /** {@code input swipe x1 y1 x2 y2 durationMs} — a straight drag/swipe gesture. */
    public void swipe(int x1, int y1, int x2, int y2, long durationMs) {
        shell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + Math.max(0, durationMs));
    }

    /** {@code input keyevent <code>} — a hardware/soft key by Android keycode. */
    public void key(int keyCode) {
        shell("input keyevent " + keyCode);
    }

    /** {@code input text <text>} — types text into the focused field (spaces escaped for the shell). */
    public void text(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        shell("input text " + text.replace(" ", "%s"));
    }

    /**
     * Launches an installed app by package via the monkey launcher-intent trick, returning what monkey said.
     *
     * <p>The output is returned rather than discarded because monkey <em>reports</em> its failures on stdout
     * with a zero exit: a package with no launcher activity (or none installed at all) prints "No activities
     * found to run", which used to be indistinguishable from a successful start. {@link #startedApp} is the
     * reading of it; callers that only want the verdict should use that.
     */
    public String startApp(String packageName) {
        return shell("monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1");
    }

    /** Whether {@link #startApp}'s output reports an actual launch rather than monkey's no-activities notice. */
    public static boolean startedApp(String monkeyOutput) {
        if (monkeyOutput == null || monkeyOutput.isBlank()) {
            return false;
        }
        String lower = monkeyOutput.toLowerCase();
        for (String failure : MONKEY_FAILURES) {
            if (lower.contains(failure)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The phrases monkey prints on stdout when a launch did <em>not</em> happen — it exits zero either way,
     * so this list is the whole verdict. Lower-case because {@link #startedApp} folds the output first.
     */
    private static final String[] MONKEY_FAILURES =
            {"no activities found", "error:", "aborted", "not found"};

    /** Reads a system property ({@code getprop <key>}), trimmed; empty string if unset. */
    public String getProp(String key) {
        return shell("getprop " + key).trim();
    }

    /**
     * Whether Android has finished booting ({@code sys.boot_completed}) — the difference between an emulator
     * whose {@code adbd} is listening and one that can actually start an app.
     *
     * <p>Those are minutes apart on a container: the ADB port answers as soon as the network is up, long
     * before the package manager will resolve a launcher intent. Every readiness question in the repo used to
     * be the TCP probe alone, which is why a launch fired into a half-booted system and did nothing.
     */
    public boolean bootCompleted() {
        try {
            return "1".equals(getProp("sys.boot_completed"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The third-party (user-installed) packages on the device — the games/apps a bot would target — via
     * {@code pm list packages -3}. System apps are excluded. Never throws; empty list on any failure.
     */
    public List<String> installedApps() {
        try {
            return parsePackageList(shell("pm list packages -3"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * {@code packageName}'s launcher icon, or {@code null} when it has none we can read.
     *
     * <p>Reads it straight out of the installed APK over four bounded byte ranges rather than pulling the
     * file — see {@link ApkIcon} for why a hundreds-of-megabytes game is not an obstacle. Best-effort like
     * everything else here: an unreadable archive is a missing thumbnail, never an exception.
     */
    public BufferedImage appIcon(String packageName) {
        String path = apkPath(packageName);
        if (path == null) {
            return null;
        }
        return ApkIcon.read(new ApkIcon.Reader() {
            @Override
            public long size() {
                return fileSize(path);
            }

            @Override
            public byte[] read(long offset, int length) {
                return readBytes(path, offset, length);
            }
        });
    }

    /** The on-device path of {@code packageName}'s base APK ({@code pm path}), or {@code null}. */
    public String apkPath(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null;
        }
        try {
            return parseApkPath(shell("pm path " + packageName.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A file's length in bytes, or {@code -1}. {@code stat -c %s} is toybox's spelling and is what Android
     * ships; a device without it simply reports "unknown", which every caller treats as "can't read this".
     */
    public long fileSize(String path) {
        try {
            return Long.parseLong(shell("stat -c %s " + path).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** ADB's transfer block size for {@link #readBytes} — {@code dd} counts in these, so offsets round to it. */
    private static final int BLOCK = 512;

    /**
     * Up to {@code length} bytes of a device file starting at {@code offset}; empty on any failure.
     *
     * <p>Goes through {@code dd} on the {@code exec:} service — the same binary-safe channel as
     * {@link #screencap()}, since {@code shell:} would translate newlines through a PTY and corrupt the
     * bytes. {@code dd} counts in blocks rather than bytes on Android's toybox, so the range is widened to
     * block boundaries and trimmed here; that is portable in a way {@code iflag=skip_bytes} is not.
     */
    public byte[] readBytes(String path, long offset, int length) {
        if (path == null || offset < 0 || length <= 0) {
            return new byte[0];
        }
        long firstBlock = offset / BLOCK;
        int into = (int) (offset - firstBlock * BLOCK);
        long blocks = ((long) into + length + BLOCK - 1) / BLOCK;
        try (AdbStream stream = dadb.open("exec:dd if=" + path + " bs=" + BLOCK + " skip=" + firstBlock
                + " count=" + blocks + " 2>/dev/null")) {
            byte[] all = stream.getSource().readByteArray();
            if (all.length <= into) {
                return new byte[0];
            }
            return java.util.Arrays.copyOfRange(all, into, Math.min(all.length, into + length));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * Picks the base APK out of {@code pm path} output. A split-install app prints several lines; the base is
     * the one we want, and it is the only one not named {@code split_*}.
     */
    static String parseApkPath(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String fallback = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("package:")) {
                continue;
            }
            String path = trimmed.substring("package:".length()).trim();
            if (path.isEmpty()) {
                continue;
            }
            if (path.endsWith("/base.apk")) {
                return path;
            }
            if (fallback == null) {
                fallback = path;
            }
        }
        return fallback;
    }

    /** Whether {@code packageName} is installed (system or user), via an exact {@code pm list packages} match. */
    public boolean isInstalled(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        try {
            return parsePackageList(shell("pm list packages " + packageName)).contains(packageName.trim());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The package name of the app currently in the foreground, or {@code ""} if none/unknown. Reads
     * {@code dumpsys activity activities} and picks the resumed activity's package.
     */
    public String currentApp() {
        try {
            return parseForegroundPackage(shell("dumpsys activity activities"));
        } catch (Exception e) {
            return "";
        }
    }

    // `package:com.foo.bar` per line (pm may append `=<path>` with -f, which we don't use); order preserved.
    private static final Pattern PACKAGE_LINE = Pattern.compile("^package:(\\S+?)(?:=.*)?$", Pattern.MULTILINE);
    // The resumed/focused activity's `<pkg>/<activity>` component in a dumpsys line.
    private static final Pattern RESUMED_ACTIVITY =
            Pattern.compile("(?:mResumedActivity|mFocusedActivity|topResumedActivity)\\S*.*?\\s([\\w.]+)/[\\w.$]+");

    /** Parses {@code pm list packages} output into package names, de-duped, in first-seen order. */
    static List<String> parsePackageList(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        Matcher m = PACKAGE_LINE.matcher(output);
        while (m.find()) {
            packages.add(m.group(1).trim());
        }
        return new ArrayList<>(packages);
    }

    /** Extracts the foreground package from {@code dumpsys activity activities} output, or {@code ""}. */
    static String parseForegroundPackage(String dumpsys) {
        if (dumpsys == null || dumpsys.isBlank()) {
            return "";
        }
        Matcher m = RESUMED_ACTIVITY.matcher(dumpsys);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Runs a shell command and returns its stdout. A non-zero exit code is not an error here — callers that
     * care can inspect the output.
     *
     * <p>Goes through a shell held open across calls ({@link AdbShellSession}) rather than forking one per
     * command, which is what makes {@link #tap} and friends cost a write and a read instead of a stream setup
     * and a process spawn. A shell that has died is re-opened once, and the command is only re-sent when it
     * provably never left this machine — see {@link AdbShellSession.Failed#delivered()}.
     */
    public String shell(String command) {
        try {
            return session().run(command);
        } catch (AdbShellSession.Failed e) {
            dropSession();
            if (e.delivered()) {
                throw new RuntimeException("shell '" + command + "' failed on " + endpoint() + ": "
                        + e.getMessage(), e);
            }
            try {
                return session().run(command);
            } catch (Exception retry) {
                throw new RuntimeException("shell '" + command + "' failed on " + endpoint() + ": "
                        + retry.getMessage(), retry);
            }
        } catch (Exception e) {
            dropSession();
            throw new RuntimeException("shell '" + command + "' failed on " + endpoint() + ": "
                    + e.getMessage(), e);
        }
    }

    /** The held shell, opened on first use. */
    private synchronized AdbShellSession session() throws AdbShellSession.Failed {
        if (session == null) {
            try {
                session = AdbShellSession.open(dadb);
            } catch (Exception e) {
                throw new AdbShellSession.Failed("could not open a shell on " + endpoint(), e, false);
            }
        }
        return session;
    }

    private synchronized void dropSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /**
     * Opens one ADB service and hands back its stream — {@code shell:…} for a long-running process,
     * {@code localabstract:…} for a socket that process is listening on.
     *
     * <p>The caller owns it and <b>must drain it</b>; see {@link DeviceStream}. This is the seam the streaming
     * channel in {@code com.botmaker.shared.device} is built on, and it is the only reason that package needs
     * nothing from {@code dadb}.
     *
     * @throws IOException when the service cannot be opened — for {@code localabstract:} that is the normal
     *                     answer while the far end has not started listening yet, so callers retry rather
     *                     than treat it as fatal
     */
    public DeviceStream openService(String service) throws IOException {
        return new DeviceStream(dadb.open(service));
    }

    /**
     * Copies a local file to {@code remotePath} on the device, executable. Returns whether it landed.
     *
     * <p>Best-effort like the rest of this class: a device that refuses the write (a locked-down {@code
     * /data/local/tmp}, a full filesystem) is a {@code false} the caller degrades on, not an exception into a
     * bring-up path that has a working fallback.
     */
    public boolean push(java.io.File file, String remotePath) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            dadb.push(file, remotePath, 0755, file.lastModified() / 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the connection still answers a trivial shell round-trip. */
    public boolean isConnected() {
        try {
            dadb.shell("true");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        dropSession();
        try {
            dadb.close();
        } catch (Exception ignored) {
            // best-effort teardown
        }
    }
}
