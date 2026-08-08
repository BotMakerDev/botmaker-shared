package com.botmaker.shared.emulator;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The host's adb server</b> — whether one is running, what devices it owns, and whether an {@code adb}
 * binary could start one. This is the only part of the stack that is not pure dadb, and it exists for exactly
 * one reason: <b>a phone on a USB cable</b>.
 *
 * <p>{@link AdbDevice} speaks the ADB wire protocol itself over TCP and does its own RSA auth, which is why
 * this repo ships no {@code adb} binary and never has. That covers every emulator and a phone in legacy
 * {@code adb tcpip} mode. It cannot cover two cases, and no amount of dadb can:
 *
 * <ul>
 *   <li><b>USB.</b> {@code adbd} talks the ADB protocol over a USB endpoint, not a socket. Reaching it from a
 *       JVM means either an adb server in front of it or a libusb implementation of the transport.</li>
 *   <li><b>Android 11+ "Wireless debugging"</b> (the pairing-code kind), which is TLS-wrapped. dadb 1.2.9
 *       implements no STLS at all; the {@code adb} binary does.</li>
 * </ul>
 *
 * <p>So an adb server is an <b>optional capability that unlocks cable and pairing-code wireless</b>, never a
 * requirement — the same shape as {@code SessionBackends.installHint} for a missing gamescope. Nothing here
 * throws, and everything degrades to "no server, no devices".
 *
 * <p><b>The device list is read by speaking the server's protocol directly</b> rather than through dadb's own
 * {@code AdbServer.listDadbs()}, which opens a live connection per device. A picker enumerating rows must not
 * pay a handshake per row, and enumeration wants the {@code model}/{@code transport} fields that only
 * {@code host:devices-l} carries.
 */
public final class AdbTools {

    private AdbTools() {}

    /** The port every adb server binds by default. Overridable per call for a test or an unusual setup. */
    public static final int DEFAULT_PORT = 5037;

    /** Connect/read timeout for talking to the server — it is loopback, so this is generous. */
    private static final int TIMEOUT_MS = 1_000;

    /**
     * One device as the adb server sees it.
     *
     * @param serial the server's own name for it — a hardware serial over USB, or {@code ip:port} for a
     *               device connected over the network. This is what {@code host:transport:<serial>} takes.
     * @param state  the raw state word: {@code device}, {@code offline}, {@code unauthorized}, …
     * @param model  {@code ro.product.model} as the server reports it (underscores and all), or {@code ""}
     * @param usb    whether the server reported a {@code usb:} path for it — i.e. it is on a cable
     */
    public record ServerDevice(String serial, String state, String model, boolean usb) {

        /** Whether this device can actually be driven. {@code unauthorized} is the un-accepted RSA prompt. */
        public boolean online() {
            return "device".equals(state);
        }

        /**
         * Whether this is one of the emulators the {@link Platforms} products discover for themselves. The adb
         * server names a local Android emulator {@code emulator-<port>}; a physical device never looks like
         * that. See {@link DevicePlatform} for why this filter is the difference between one phone and two.
         */
        public boolean emulator() {
            return serial.startsWith("emulator-");
        }

        /** A display name: the model with underscores restored to spaces, falling back to the serial. */
        public String displayName() {
            return model.isBlank() ? serial : model.replace('_', ' ');
        }
    }

    /** Whether an adb server is already listening. Cheap; safe to call from a picker. */
    public static boolean serverRunning() {
        return serverRunning(DEFAULT_PORT);
    }

    static boolean serverRunning(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Every device a running adb server owns, or an empty list when no server is running.
     *
     * <p><b>Deliberately does not start one.</b> Enumeration happens on pickers and poll loops, and spawning a
     * background daemon as a side effect of drawing a list is not something a user asked for. Starting the
     * server is {@link #startServer()}, which a UI calls once, on purpose.
     */
    public static List<ServerDevice> devices() {
        return devices(DEFAULT_PORT);
    }

    static List<ServerDevice> devices(int port) {
        try {
            return parseDevices(query(port, "host:devices-l"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Asks a running adb server one {@code host:} question and returns its payload.
     *
     * <p>The protocol is four hex digits of length, then the request; the reply is {@code OKAY} (or
     * {@code FAIL}) followed by the same four-hex-digit framing around the payload.
     */
    private static String query(int port, String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(String.format(Locale.ROOT, "%04x%s", request.length(), request)
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            if (!"OKAY".equals(readExactly(in, 4))) {
                return "";
            }
            int length = Integer.parseInt(readExactly(in, 4).trim(), 16);
            return length <= 0 ? "" : readExactly(in, length);
        }
    }

    /** Reads exactly {@code count} bytes as UTF-8, or throws if the stream ends first. */
    private static String readExactly(InputStream in, int count) throws Exception {
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(buffer, read, count - read);
            if (n < 0) {
                throw new java.io.EOFException("adb server closed after " + read + " of " + count + " bytes");
            }
            read += n;
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }

    /**
     * Parses {@code host:devices-l} output. Pure and package-visible so the shapes below are asserted without
     * an adb server:
     *
     * <pre>
     * R5CT30ABCDE        device usb:1-4 product:x1q model:SM_G981B device:x1q transport_id:1
     * emulator-5554      device product:sdk_gphone64 model:sdk_gphone64 device:emu64x transport_id:2
     * 192.168.1.5:5555   device product:panther model:Pixel_7 device:panther transport_id:3
     * 0A1B2C3D           unauthorized usb:1-2 transport_id:4
     * </pre>
     *
     * <p>An {@code unauthorized} line is kept rather than dropped: a phone whose "Allow USB debugging?" prompt
     * has not been accepted is the single most likely reason a user's device does not appear, and a row that
     * says so is worth far more than a list that silently omits it.
     */
    static List<ServerDevice> parseDevices(String output) {
        List<ServerDevice> devices = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return devices;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices")) {
                continue;
            }
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 2) {
                continue;
            }
            Map<String, String> tags = new LinkedHashMap<>();
            for (int i = 2; i < fields.length; i++) {
                int colon = fields[i].indexOf(':');
                if (colon > 0) {
                    tags.put(fields[i].substring(0, colon), fields[i].substring(colon + 1));
                }
            }
            devices.add(new ServerDevice(fields[0], fields[1], tags.getOrDefault("model", ""),
                    tags.containsKey("usb")));
        }
        return devices;
    }

    /**
     * The {@code adb} binary, if one can be found — {@code PATH} first, then
     * {@code $ANDROID_HOME}/{@code $ANDROID_SDK_ROOT}{@code /platform-tools}. Same two places dadb's own
     * {@code AdbBinary} looks, single-sourced here so the availability answer and the hint agree.
     */
    public static Optional<File> binary() {
        File onPath = fromPath();
        if (onPath != null) {
            return Optional.of(onPath);
        }
        String home = System.getenv("ANDROID_HOME") != null
                ? System.getenv("ANDROID_HOME")
                : System.getenv("ANDROID_SDK_ROOT");
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        File candidate = new File(new File(home, "platform-tools"), binaryName());
        return candidate.isFile() ? Optional.of(candidate) : Optional.empty();
    }

    private static String binaryName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "adb.exe" : "adb";
    }

    /** The first {@code adb} on {@code PATH}, or null. Walks {@code PATH} rather than spawning {@code which}. */
    private static File fromPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, binaryName());
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether cable and TLS-wireless devices are reachable at all — either a server is already up, or a binary
     * exists that could start one. False means {@link #installHint()} is what a user needs to see.
     */
    public static boolean available() {
        return serverRunning() || binary().isPresent();
    }

    /**
     * Starts an adb server with the discovered binary. Returns whether one is running afterwards.
     *
     * <p>Separate from {@link #devices()} on purpose: enumeration must stay a read. This is the explicit,
     * user-initiated act.
     */
    public static boolean startServer() {
        if (serverRunning()) {
            return true;
        }
        Optional<File> adb = binary();
        if (adb.isEmpty()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(adb.get().getAbsolutePath(), "start-server")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
        return serverRunning();
    }

    /** The one-line, user-facing sentence for a machine that cannot reach cabled or TLS-wireless devices. */
    public static String installHint() {
        return "install Android platform-tools (the `adb` command) to use a phone over USB, or Android 11+ "
                + "wireless debugging — a phone already in `adb tcpip` mode needs none of this and can be "
                + "added by address";
    }
}
