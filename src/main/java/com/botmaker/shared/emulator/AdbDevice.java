package com.botmaker.shared.emulator;

import dadb.AdbShellResponse;
import dadb.AdbStream;
import dadb.Dadb;

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
 * <p>Screen capture uses {@code exec:screencap -p}: the {@code exec:} ADB service pipes the command's raw
 * stdout with no PTY newline translation, so the PNG bytes arrive intact (the legacy {@code shell:} service
 * corrupts binary). Kept off the hot path otherwise — a capture is a full-frame PNG, which is the throughput
 * ceiling of this transport (see the ROADMAP note on a native-window capture backend).
 */
public final class AdbDevice implements AutoCloseable {

    private final String host;
    private final int port;
    private final Dadb dadb;

    private AdbDevice(String host, int port, Dadb dadb) {
        this.host = host;
        this.port = port;
        this.dadb = dadb;
    }

    /**
     * Opens a connection to {@code host:port} (typically {@code 127.0.0.1:<instance adb port>}).
     *
     * @throws RuntimeException if the connection can't be established (emulator not running, ADB disabled in
     *                          the emulator's settings, or the port is wrong)
     */
    public static AdbDevice connect(String host, int port) {
        try {
            Dadb dadb = Dadb.create(host, port);
            return new AdbDevice(host, port, dadb);
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to emulator ADB at " + host + ":" + port
                    + ". Is the emulator running with ADB enabled? " + e.getMessage(), e);
        }
    }

    /** {@code 127.0.0.1:<port>} label, for logging / identity. */
    public String endpoint() {
        return host + ":" + port;
    }

    /** A raw screen grab of the emulator's framebuffer as a {@link BufferedImage}, or {@code null} on failure. */
    public BufferedImage screencap() {
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
     * Runs a shell command and returns its stdout. A non-zero exit code is not an error here (it stays a
     * value on dadb's response) — callers that care can inspect the output.
     */
    public String shell(String command) {
        try {
            AdbShellResponse response = dadb.shell(command);
            return response.getOutput();
        } catch (Exception e) {
            throw new RuntimeException("shell '" + command + "' failed on " + endpoint() + ": " + e.getMessage(), e);
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
        try {
            dadb.close();
        } catch (Exception ignored) {
            // best-effort teardown
        }
    }
}
