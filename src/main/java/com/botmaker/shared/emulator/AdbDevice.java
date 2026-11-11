package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;
import com.botmaker.shared.steps.DeviceSteps;
import dadb.AdbStream;
import dadb.Dadb;
import dadb.adbserver.AdbServer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
        this(endpoint, dadb, null, null, STREAMED_LIMIT);
    }

    /**
     * Package-visible for the tests, which give a fake {@code dadb}, {@code uploader} and {@code streamer} and a small
     * {@code streamedLimit}; everything else connects.
     *
     * @param uploader      how an APK over the limit reaches the device, null for the adb binary's
     * @param streamer      how APKs stream into the package manager, null for the adb binary's, else dadb's
     * @param streamedLimit the largest APK file streamed into the package manager
     */
    AdbDevice(AdbEndpoint endpoint, Dadb dadb, Uploader uploader, Streamer streamer, long streamedLimit) {
        this.endpoint = endpoint;
        this.dadb = dadb;
        this.uploader = uploader != null ? uploader : this::uploadedByAdb;
        this.streamer = streamer != null ? streamer : this::streamed;
        this.streamedLimit = streamedLimit;
    }

    /** Copies a local APK to a path on the device for {@link #install}, or throws saying why not. */
    interface Uploader {
        void upload(File file, String remote) throws IOException;
    }

    /** Streams APKs, one or a base and its splits, into the device's package manager, or throws its reason. */
    interface Streamer {
        void install(List<File> apks) throws IOException;
    }

    private final Uploader uploader;
    private final Streamer streamer;
    private final long streamedLimit;

    /**
     * {@link AdbTools#install} when this computer has the adb binary, dadb's install otherwise: dadb's stream stalled
     * at the package manager on GameLoop's Android 13 (2026-11-10, 3 GB asset pack, idle after 3.2 GB) where Google's
     * carried it in 159 s, as it had stalled on BlueStacks.
     */
    private void streamed(List<File> apks) throws IOException {
        if (AdbTools.binary().isPresent()) {
            AdbTools.Outcome outcome = AdbTools.install(endpoint.label(), endpoint instanceof AdbEndpoint.Tcp, apks);
            if (!outcome.ok()) throw new IOException(outcome.message());
        } else if (apks.size() == 1) {
            dadb.install(apks.getFirst(), "-r");
        } else {
            dadb.installMultiple(apks, "-r");
        }
    }

    /**
     * {@link AdbTools#push}: Google's push carries gigabytes where dadb's was reset at 138 MB (BlueStacks, 2026-11-10),
     * so with no adb binary an APK this big is refused with how to get one, rather than tried and broken.
     */
    private void uploadedByAdb(File file, String remote) throws IOException {
        if (AdbTools.binary().isEmpty()) {
            throw new IOException(file.getName() + " is over 2 GB, which only Android's own adb copies to an emulator. "
                    + AdbTools.installHint());
        }
        AdbTools.Outcome outcome = AdbTools.push(endpoint.label(), endpoint instanceof AdbEndpoint.Tcp, file, remote);
        if (!outcome.ok()) throw new IOException("adb push of " + file.getName() + " failed: " + outcome.message());
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
     *
     * <p>A grab still unanswered after {@link #GRAB_DEADLINE} closes this connection ({@link #cutAfter}), so the
     * caller gets an exception instead of waiting forever, and {@link #isConnected()} says false from then on.
     */
    public BufferedImage screencap() {
        return DeviceSteps.step(endpoint(), "screencap", this::grab,
                frame -> frame == null ? "no frame" : frame.getWidth() + "×" + frame.getHeight());
    }

    private BufferedImage grab() {
        ScheduledFuture<?> cut = cutAfter(GRAB_DEADLINE, "a frame grab");
        try {
            if (endpoint.local()) {
                BufferedImage raw = screencapRaw();
                if (raw != null) {
                    return raw;
                }
            }
            return screencapPng();
        } finally {
            cut.cancel(false);
        }
    }

    /** How long a frame grab may go unanswered: a PNG over a phone's radio takes a second or two. */
    private static final Duration GRAB_DEADLINE = Duration.ofSeconds(20);
    /** How long {@link #isConnected()}'s round trip may go unanswered. */
    private static final Duration PING_DEADLINE = Duration.ofSeconds(5);

    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "adb-deadline");
        t.setDaemon(true);
        return t;
    });

    /**
     * Closes this connection when {@code after} passes before the returned future is cancelled. The socket has no
     * read timeout of its own, and must not have one: a scrcpy stream ({@link #openService}) is quiet for as long
     * as the screen is still. Without this, a grab sent on a connection whose far end went away without a reset
     * (Waydroid's session stopped and started again under it, 2026-11-03) blocked the run for good, under the
     * watchdog, which only checks between a bot's own steps.
     */
    private ScheduledFuture<?> cutAfter(Duration after, String what) {
        return DEADLINES.schedule(() -> {
            Diag.log("[Emulator] " + what + " on " + endpoint() + " went unanswered for " + after.toSeconds()
                    + " s — closing the connection");
            close();
        }, after.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * {@code exec:screencap} with no {@code -p}: the framebuffer and its header, with no encode on the device.
     * {@code null} when the payload is not a framebuffer we can lay out — see {@link RawFramebuffer} for how
     * that is decided, and why the check is strong enough to fall back on.
     */
    public BufferedImage screencapRaw() {
        try (AdbStream stream = dadb.open("exec:screencap " + NO_STDERR)) {
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
        try (AdbStream stream = dadb.open("exec:screencap -p " + NO_STDERR)) {
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

    /**
     * Drops a frame grab's stderr on the device: {@code exec:} merges it into stdout, and Waydroid on an AMD GPU
     * starts every {@code screencap} with {@code /vendor/etc/hwdata/amdgpu.ids: No such file or directory}
     * (2026-11-03), which neither a PNG nor a raw framebuffer decodes past — the bot saw nothing.
     */
    private static final String NO_STDERR = "2>/dev/null";

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

    /**
     * {@code input text <text>} — types text into the focused field.
     *
     * <p>The argument crosses the device's {@code sh}, so it is single-quoted ({@link #inputTextArgument}).
     * It used to be pasted in bare with only spaces escaped, which broke on {@code &}, {@code ;} or a quote
     * and ran whatever followed — harmless while only a bot's own strings reached it, not once the remote
     * pilot's keyboard did (2026-09-29).
     */
    public void text(String text) {
        String argument = inputTextArgument(text);
        if (argument.isEmpty()) {
            return;
        }
        // Its step says the length only: the text may be a password a bot types into a game.
        DeviceSteps.step(endpoint(), "input text (" + text.length() + " characters)", () -> {
            try {
                return shell("input text " + argument);
            } catch (RuntimeException e) {
                // shell's own message quotes the command, and so the text.
                throw new RuntimeException("input text failed on " + endpoint());
            }
        }, result -> "");
    }

    /**
     * {@code text} as one shell word for {@code input text}: printable ASCII only (the command types nothing
     * else), a space as {@code %s} (its own escape for one), the whole wrapped in single quotes with each quote
     * written {@code '\''}. Empty when nothing typeable is left.
     */
    static String inputTextArgument(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) {
                kept.append(c == ' ' ? "%s" : c == '\'' ? "'\\''" : String.valueOf(c));
            }
        }
        return kept.isEmpty() ? "" : "'" + kept + "'";
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

    /**
     * Whether {@link #startApp}'s output reports an actual launch: monkey's "Events injected: N" with N above
     * zero, which it prints on stdout only after it ran. Its failures go to stderr, which the shell here does not
     * return, so a list of failure phrases never saw them: on Waydroid monkey stops with "SYS_KEYS has no physical
     * keys" before injecting anything, its stdout only the echo of its arguments, and that read as a start — the
     * {@code am start} rung was skipped and a recovery's restart left Android's home screen up (2026-11-03).
     */
    public static boolean startedApp(String monkeyOutput) {
        if (monkeyOutput == null) return false;
        Matcher injected = EVENTS_INJECTED.matcher(monkeyOutput);
        return injected.find() && Integer.parseInt(injected.group(1)) > 0;
    }

    private static final Pattern EVENTS_INJECTED = Pattern.compile("(?i)events injected:\\s*(\\d+)");

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
        ApkZip.Reader apk = apkReader(packageName);
        return apk == null ? null : ApkIcon.read(apk, ApkZip.entries(apk));
    }

    /**
     * {@code packageName}'s name as the launcher shows it ("Clash of Clans"), or {@code null}. See {@link #appInfo}.
     */
    public String appLabel(String packageName) {
        AppInfo info = appInfo(packageName, false);
        return info.label() == null || info.label().isEmpty() ? null : info.label();
    }

    /**
     * What one installed app's APK says about it.
     *
     * @param label the name as the launcher shows it ("Clash of Clans"); {@code ""} when the APK was read and
     *              declares none of its own (a library, a shim); {@code null} when the APK couldn't be read,
     *              which is worth asking again
     * @param icon  its launcher icon, or {@code null}
     * @param read  whether the APK's directory was read, so a {@code null} icon means it has none we can decode
     */
    public record AppInfo(String label, BufferedImage icon, boolean read) {}

    /**
     * {@code packageName}'s name and, with {@code icon}, its launcher icon, out of the installed APK: its
     * directory read once for both, then the manifest and {@code resources.arsc} ({@link ApkLabel}; the table is
     * read whole, a few megabytes for a game, so ask once per app and keep the answer). Never throws.
     */
    public AppInfo appInfo(String packageName, boolean icon) {
        ApkZip.Reader apk = apkReader(packageName);
        if (apk == null) return new AppInfo(null, null, false);
        var entries = ApkZip.entries(apk);
        if (entries.isEmpty()) return new AppInfo(null, null, false);
        return new AppInfo(ApkLabel.read(apk, entries), icon ? ApkIcon.read(apk, entries) : null, true);
    }

    /** Ranged reads of {@code packageName}'s base APK, or {@code null} when it has none. */
    private ApkZip.Reader apkReader(String packageName) {
        String path = apkPath(packageName);
        if (path == null) {
            return null;
        }
        return new ApkZip.Reader() {
            @Override
            public long size() {
                return fileSize(path);
            }

            @Override
            public byte[] read(long offset, int length) {
                return readBytes(path, offset, length);
            }
        };
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
            return Arrays.copyOfRange(all, into, Math.min(all.length, into + length));
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
        return DeviceSteps.step(endpoint(), command, () -> shellOnce(command), DeviceSteps::clip);
    }

    private String shellOnce(String command) {
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
     * The package of the window that has focus, which is the app a bot means: a permission dialog's package
     * while one is up, rather than the activity behind it {@link #currentApp()} reads. Empty when unknown.
     */
    public Optional<String> focusedPackage() {
        try {
            return parseFocusedPackage(shell(FOCUS));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * The focus lines of each display, now. Not the whole {@code dumpsys window}: its first section is the last ANR's
     * snapshot, whose focus line stays the app's long after, so a dialog over it read as the app (Waydroid,
     * 2026-11-01).
     */
    static final String FOCUS = "dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp'";

    /** Android's own dialog in focus over the app ({@link SystemDialog}), or empty, also when the shell fails. */
    public Optional<SystemDialog.Shown> systemDialog() {
        try {
            return SystemDialog.over(shell(FOCUS));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // `mCurrentFocus=Window{1a2b u0 com.foo/com.foo.Main}`; `mFocusedApp=ActivityRecord{3c4d u0 com.foo/.Main t12}`.
    private static final Pattern CURRENT_FOCUS = Pattern.compile("mCurrentFocus=Window\\{\\S+ \\S+ ([\\w.]+)/");
    private static final Pattern FOCUSED_APP = Pattern.compile("mFocusedApp=\\S*\\{\\S+ \\S+ ([\\w.]+)/");

    /**
     * The focused window's package from {@code dumpsys window}, else the focused activity's: a window with no
     * component ({@code StatusBar}, a {@code PopupWindow}) names no package.
     */
    static Optional<String> parseFocusedPackage(String dumpsys) {
        if (dumpsys == null) return Optional.empty();
        for (Pattern pattern : List.of(CURRENT_FOCUS, FOCUSED_APP)) {
            Matcher m = pattern.matcher(dumpsys);
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    /**
     * Copies a local file to {@code remotePath} on the device, executable. Returns whether it landed.
     *
     * <p>Best-effort like the rest of this class: a device that refuses the write (a locked-down {@code
     * /data/local/tmp}, a full filesystem) is a {@code false} the caller degrades on, not an exception into a
     * bring-up path that has a working fallback.
     */
    public boolean push(File file, String remotePath) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            DeviceSteps.step(endpoint(), "push " + file.getName() + " " + remotePath, () -> {
                dadb.push(file, remotePath, 0755, file.lastModified());
                return null;
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Installs an app from APK files on this computer: one file, or a base APK and its splits together, replacing
     * an older version. Throws with the package manager's own reason ("INSTALL_FAILED_NO_MATCHING_ABIS…") when
     * Android refuses it.
     */
    public void install(List<File> apks) throws IOException {
        String command = "install " + String.join(" ", apks.stream().map(File::getName).toList());
        DeviceSteps.step(endpoint(), command, () -> {
            if (apks.stream().anyMatch(apk -> apk.length() > streamedLimit) && sdk() <= LAST_UNSTREAMED_SDK) {
                installUploaded(apks, DeviceSteps.progress(endpoint(), command));
            } else {
                streamer.install(apks);
            }
            return null;
        });
    }

    /**
     * The largest APK file streamed into the package manager: a bigger one stalls there (2026-11-10, BlueStacks on
     * Android 9: Sword x Staff's 3.08 GB asset pack stopped at 0.5%, and Google's {@code adb install-multiple} failed to
     * write it too), so it is uploaded first, by the adb binary ({@link #uploadedByAdb}), and installed from the
     * device's copy.
     */
    static final long STREAMED_LIMIT = Integer.MAX_VALUE;
    /**
     * The newest Android that never takes a streamed APK over {@link #STREAMED_LIMIT}: 9 (API 28), seen on BlueStacks.
     * A newer one streams it, and must: LDPlayer's Android 14 took the same 3 GB pack streamed but refused it written
     * by path ("Split null was defined multiple times"), and GameLoop's Android 13 took it streamed by Google's adb. Android
     * 10 to 12 are untried and stream.
     */
    static final int LAST_UNSTREAMED_SDK = 28;

    /** The device's API level, or {@link Integer#MAX_VALUE} when it doesn't say: streamed, as before. */
    private int sdk() {
        try {
            return Integer.parseInt(dadb.shell("getprop ro.build.version.sdk").getAllOutput().strip());
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
    private static final Pattern SESSION = Pattern.compile("\\[(\\d+)]");
    private static final String COPY = "/data/local/tmp/botmaker-install.apk";

    /**
     * Installs {@code apks} through a package manager session that reads each from a copy on the device, one at a
     * time: uploaded, written into the session, deleted, so the device holds one copy beside what the session staged.
     * A copy goes whatever happens, and a session not committed is abandoned.
     */
    private void installUploaded(List<File> apks, DeviceSteps.Progress progress) throws IOException {
        long total = apks.stream().mapToLong(File::length).sum();
        long[] before = {0};
        String created = dadb.shell("pm install-create -r -S " + total).getAllOutput();
        Matcher session = SESSION.matcher(created);
        if (!session.find()) throw new IOException("The package manager opened no install: " + created.strip());
        String id = session.group(1);
        boolean committed = false;
        try {
            for (int i = 0; i < apks.size(); i++) {
                File apk = apks.get(i);
                String wrote;
                long start = before[0];
                // The tick reads only finals: a late one, past cancel, never counts this file twice.
                ScheduledFuture<?> told = progress == DeviceSteps.Progress.NONE ? null
                        : PROGRESS.scheduleWithFixedDelay(() -> progress.report(
                                start + Math.min(copied(), apk.length()), total), 1, 1, TimeUnit.SECONDS);
                try {
                    uploader.upload(apk, COPY);
                    if (told != null) told.cancel(false);
                    before[0] += apk.length();
                    progress.report(before[0], total);
                    wrote = dadb.shell("pm install-write -S " + apk.length() + " " + id + " " + i + " " + COPY)
                            .getAllOutput();
                } finally {
                    if (told != null) told.cancel(false);
                    removeQuietly(COPY);
                }
                if (!wrote.contains("Success")) {
                    throw new IOException("The package manager didn't take " + apk.getName() + ": " + wrote.strip());
                }
            }
            String said = dadb.shell("pm install-commit " + id).getAllOutput().strip();
            committed = true;
            if (!said.startsWith("Success")) throw new IOException(said);
        } finally {
            if (!committed) {
                try {
                    dadb.shell("pm install-abandon " + id);
                } catch (Exception e) {
                    Diag.log("[Emulator] couldn't abandon install session " + id + ": " + e.getMessage());
                }
            }
        }
    }

    /** Reads an upload's progress each second, apart from {@link #DEADLINES}, which a slow read must never hold up. */
    private static final ScheduledExecutorService PROGRESS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "adb-upload-progress");
        t.setDaemon(true);
        return t;
    });

    /** The bytes of the upload's copy on the device so far, 0 while it isn't there or doesn't say. */
    private long copied() {
        try {
            return Long.parseLong(dadb.shell("stat -c %s " + COPY).getAllOutput().strip());
        } catch (Exception e) {
            return 0;
        }
    }

    private void removeQuietly(String path) {
        try {
            dadb.shell("rm -f " + path);
        } catch (Exception e) {
            Diag.log("[Emulator] couldn't remove " + path + ": " + e.getMessage());
        }
    }

    /**
     * The {@code versionCode} of {@code packageName} as installed, or {@code -1} when it isn't installed. Throws when
     * the question itself fails, so a dropped connection never reads as "not installed".
     */
    public long installedVersion(String packageName) {
        return parseVersionCode(shell("dumpsys package " + packageName.replaceAll("[^A-Za-z0-9._]", "")));
    }

    private static final Pattern VERSION_CODE = Pattern.compile("\\bversionCode=(\\d+)");

    /**
     * The first {@code versionCode=} in {@code dumpsys package <pkg>}, or {@code -1}: a package not installed prints
     * no {@code Packages:} entry for it. With several (an update over a system app), the first is the one running.
     * Pure, for the tests.
     */
    static long parseVersionCode(String dumpsys) {
        if (dumpsys == null) return -1;
        Matcher m = VERSION_CODE.matcher(dumpsys);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Copies a local file to {@code remotePath}, readable, creating its folders; throws when it can't. */
    public void pushData(File file, String remotePath) throws IOException {
        int slash = remotePath.lastIndexOf('/');
        DeviceSteps.step(endpoint(), "push " + file.getName() + " " + remotePath, () -> {
            if (slash > 0) shell("mkdir -p '" + remotePath.substring(0, slash).replace("'", "") + "'");
            // dadb takes milliseconds and converts them itself.
            dadb.push(file, remotePath, 0644, file.lastModified());
            return null;
        });
    }

    /** Whether the connection still answers a trivial shell round-trip. */
    public boolean isConnected() {
        ScheduledFuture<?> cut = cutAfter(PING_DEADLINE, "a liveness check");
        try {
            dadb.shell("true");
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            cut.cancel(false);
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
