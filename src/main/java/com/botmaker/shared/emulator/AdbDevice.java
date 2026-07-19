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

    /** Launches an installed app by package via the monkey launcher-intent trick. */
    public void startApp(String packageName) {
        shell("monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1");
    }

    /** Reads a system property ({@code getprop <key>}), trimmed; empty string if unset. */
    public String getProp(String key) {
        return shell("getprop " + key).trim();
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
