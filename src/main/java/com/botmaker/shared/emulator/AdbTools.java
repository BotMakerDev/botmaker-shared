package com.botmaker.shared.emulator;

import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;
import com.botmaker.shared.tools.ManagedTools;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 * <p><b>Optional no longer means the user's problem.</b> The repo still vendors no binary, but {@link
 * ManagedTools} can fetch Google's pinned one on request, and {@link #binary()} finds it as a last resort — so
 * the absence above is a download rather than an errand. {@link #pair} is what that unlocks: the pairing-code
 * route needs the binary and exists nowhere else.
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
     * The {@code adb} binary, if one can be found, in the order they should win:
     *
     * <ol>
     *   <li>{@code PATH},</li>
     *   <li>{@code $ANDROID_HOME}/{@code $ANDROID_SDK_ROOT}{@code /platform-tools} — the same two places dadb's
     *       own {@code AdbBinary} looks, single-sourced here so the availability answer and the hint agree,</li>
     *   <li>{@link ManagedTools#adb()}, the copy BotMaker downloaded for itself.</li>
     * </ol>
     *
     * <p><b>The managed copy is deliberately last.</b> A machine with a real Android SDK on it has one adb that
     * its emulators, its IDE and its server are already agreed on; a second one we fetched must not quietly
     * displace it — an adb server is a singleton on port 5037 and two binaries fighting over it is a worse
     * failure than the one this fallback fixes.
     */
    public static Optional<File> binary() {
        Optional<File> onPath = Executables.find(binaryName());
        if (onPath.isPresent()) {
            return onPath;
        }
        String home = System.getenv("ANDROID_HOME") != null
                ? System.getenv("ANDROID_HOME")
                : System.getenv("ANDROID_SDK_ROOT");
        if (home != null && !home.isBlank()) {
            File candidate = new File(new File(home, "platform-tools"), binaryName());
            if (candidate.isFile()) {
                return Optional.of(candidate);
            }
        }
        return ManagedTools.adb();
    }

    /**
     * Whether the binary in use is the one BotMaker downloaded rather than one the user already had — which is
     * the difference between "found yours" and "downloaded ours", and the only thing a UI needs in order to
     * stop offering a download it has already made.
     */
    public static boolean managed() {
        Optional<File> found = binary();
        Optional<File> ours = ManagedTools.adb();
        return found.isPresent() && ours.isPresent()
                && found.get().getAbsolutePath().equals(ours.get().getAbsolutePath());
    }

    private static String binaryName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "adb.exe" : "adb";
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

    /**
     * What one {@code adb} command did, in the only two terms a caller has: whether it worked, and what adb
     * itself said about it.
     *
     * <p>The message is passed through rather than replaced because <b>the real reason is always more useful
     * than our summary of it</b>. "Failed: Wrong password or connection was dropped" tells a user to re-read the
     * code off their phone; "pairing failed" tells them nothing they could act on.
     *
     * @param ok      whether the command achieved what it was asked to do — read out of the output, not out of
     *                the exit status, because {@code adb connect} exits 0 even when it says it failed
     * @param message adb's own words, trimmed; a timeout or a missing binary produces our own sentence instead
     */
    public record Outcome(boolean ok, String message) {}

    /** Pairing and connecting are both a round trip to a phone that may not be listening; bound the wait. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

    /**
     * <b>Android 11+ wireless pairing</b> — {@code adb pair <host:port> <code>}, with the host, port and
     * six-digit code from Developer options ▸ Wireless debugging ▸ <i>Pair device with pairing code</i>.
     *
     * <p>This is the one route to a phone that never needs a cable at all, and it is only reachable through the
     * binary: the exchange is TLS-wrapped ({@code STLS}) and dadb implements none of it. Note that the pairing
     * port is <b>not</b> the debugging port — the dialog shows a different, short-lived one for pairing, and
     * connecting afterwards uses the one on the wireless-debugging screen itself.
     *
     * <p>Pairing is persistent: it is done once per phone, and {@link #connect} is what every later session
     * needs.
     */
    public static Outcome pair(String hostPort, String code) {
        if (hostPort == null || hostPort.isBlank() || code == null || code.isBlank()) {
            return new Outcome(false, "a host:port and a pairing code are both required");
        }
        return run(List.of("pair", hostPort.trim(), code.trim()), "Successfully paired");
    }

    /** {@code adb connect <host:port>} — the debugging port, after {@link #pair} or an {@code adb tcpip}. */
    public static Outcome connect(String hostPort) {
        if (hostPort == null || hostPort.isBlank()) {
            return new Outcome(false, "a host:port is required");
        }
        // "already connected to" also contains it, and is a success by any measure the caller cares about.
        return run(List.of("connect", hostPort.trim()), "connected to");
    }

    // --- phones that announce themselves on the network ---

    /** The kinds of service an Android phone announces over mDNS for adb, by their DNS-SD type. */
    public enum MdnsKind {
        /** The short-lived <i>Pair device with QR code / pairing code</i> port. */
        PAIRING("_adb-tls-pairing._tcp", "ready to pair"),
        /** Android 11+ wireless debugging's own port, the one {@link #connect} takes. */
        CONNECT("_adb-tls-connect._tcp", "wireless debugging"),
        /** A phone in legacy {@code adb tcpip} mode, on builds that announce it. */
        LEGACY("_adb._tcp", "adb over Wi-Fi"),
        UNKNOWN("", "unknown");

        private final String id;
        private final String displayName;

        MdnsKind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The kind a listed service type names; older adb builds end it with a dot. Never throws. */
        public static MdnsKind fromId(String id) {
            String type = id == null ? "" : id.strip();
            if (type.endsWith(".")) type = type.substring(0, type.length() - 1);
            for (MdnsKind kind : values()) {
                if (kind != UNKNOWN && kind.id.equals(type)) return kind;
            }
            return UNKNOWN;
        }
    }

    /**
     * One service the adb server heard announced.
     *
     * @param instance the announced name: {@code adb-<serial>-<suffix>} for a phone's own services, or the name
     *                 a QR code gave it while it pairs
     * @param address  {@code host:port}
     */
    public record MdnsService(String instance, MdnsKind kind, String address) {

        /** The address without its port. */
        public String host() {
            int colon = address.lastIndexOf(':');
            return colon < 0 ? address : address.substring(0, colon);
        }

        /** The phone's serial out of {@code adb-<serial>-<suffix>}, else the announced name as is. */
        public String displayName() {
            if (!instance.startsWith("adb-")) return instance;
            String rest = instance.substring(4);
            int dash = rest.lastIndexOf('-');
            return dash > 0 ? rest.substring(0, dash) : rest;
        }

        /**
         * Whether {@code device} is this service's phone, already connected: by its address, by the name adb's
         * own mDNS connect gave it, or by its serial when it is on a cable.
         */
        public boolean is(ServerDevice device) {
            return device.serial().equals(address) || device.serial().startsWith(instance + ".")
                    || device.serial().equals(displayName());
        }
    }

    /**
     * What a running adb server has heard announced on the local network, or nothing when no server runs.
     * Asked over the server's socket like {@link #devices()}, so it stays a read: it never starts a server.
     */
    public static List<MdnsService> mdnsServices() {
        try {
            return parseMdns(query(DEFAULT_PORT, "host:mdns:services"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Parses {@code host:mdns:services}, one service per line as {@code adb mdns services} prints it:
     *
     * <pre>
     * adb-R5CT30ABCDE-vWgJpq	_adb-tls-connect._tcp	192.168.1.5:41235
     * botmaker-k3x9q2ma	_adb-tls-pairing._tcp.	192.168.1.5:37123
     * </pre>
     */
    static List<MdnsService> parseMdns(String output) {
        List<MdnsService> services = new ArrayList<>();
        if (output == null) return services;
        for (String line : output.split("\\R")) {
            // Tab-separated: an announced name may hold spaces ("Pixel 7").
            String[] fields = line.strip().split(line.contains("\t") ? "\t+" : "\\s+");
            if (fields.length < 3 || line.startsWith("List of")) continue;
            String address = fields[fields.length - 1];
            if (address.lastIndexOf(':') <= 0) continue;
            services.add(new MdnsService(fields[0], MdnsKind.fromId(fields[1]), address));
        }
        return List.copyOf(services);
    }

    /**
     * The phones announcing a debugging port the server isn't connected to: each one a Connect away, no address
     * typed. One row per address, in the order heard. Pure.
     */
    public static List<MdnsService> unconnected(List<MdnsService> services, List<ServerDevice> devices) {
        List<MdnsService> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MdnsService service : services) {
            if (service.kind() != MdnsKind.CONNECT && service.kind() != MdnsKind.LEGACY) continue;
            if (devices.stream().anyMatch(service::is) || !seen.add(service.address())) continue;
            found.add(service);
        }
        return List.copyOf(found);
    }

    /**
     * The pairing a QR code carries: Android 11+'s <i>Pair device with QR code</i> reads it, then announces a
     * pairing port under {@code name}, and {@code password} is the code {@link #pair} sends. Both are drawn from
     * letters and digits, so nothing in them needs escaping in the payload.
     */
    public record QrPairing(String name, String password) {

        private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

        /** A fresh name and password; one code per dialog. */
        public static QrPairing random() {
            SecureRandom random = new SecureRandom();
            return new QrPairing("botmaker-" + draw(random, 8), draw(random, 10));
        }

        private static String draw(SecureRandom random, int length) {
            StringBuilder text = new StringBuilder(length);
            for (int i = 0; i < length; i++) text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            return text.toString();
        }

        /** The text the QR code holds, in the Wi-Fi QR format Android's scanner takes for adb. */
        public String payload() {
            return "WIFI:T:ADB;S:" + name + ";P:" + password + ";;";
        }
    }

    /** How long a phone is waited for, once it has paired, to announce the port it is debugged on. */
    private static final Duration CONNECT_WAIT = Duration.ofSeconds(20);

    /**
     * Pairs the phone that scans {@code qr}, then connects to it: starts the adb server if it isn't running,
     * waits up to {@code wait} for the phone to announce the code's pairing port, runs {@link #pair}, then
     * connects to the debugging port the phone announces next (or finds adb already connected to it — adb
     * connects to a paired phone it hears by itself). Blocking; stops with "Stopped." when the thread is
     * interrupted. {@code progress} gets one sentence per step.
     */
    public static Outcome pairByQr(QrPairing qr, Duration wait, Consumer<String> progress) {
        if (!startServer()) {
            return new Outcome(false, binary().isEmpty() ? installHint() : "The adb server didn't start.");
        }
        progress.accept("On the phone: Developer options ▸ Wireless debugging ▸ Pair device with QR code, "
                + "then scan this code. The phone must be on this computer's Wi-Fi.");
        try {
            MdnsService pairing = await(wait, () -> mdnsServices().stream()
                    .filter(s -> s.kind() == MdnsKind.PAIRING && s.instance().equals(qr.name())).findFirst());
            if (pairing == null) {
                String within = wait.toSeconds() < 120 ? wait.toSeconds() + " seconds" : wait.toMinutes() + " minutes";
                return new Outcome(false, "No phone scanned the code within " + within + ". "
                        + "Check that it's on the same Wi-Fi as this computer, then try again.");
            }
            progress.accept("Pairing with " + pairing.host() + "…");
            Outcome paired = pair(pairing.address(), qr.password());
            if (!paired.ok()) return paired;
            progress.accept("Paired. Connecting…");
            String host = pairing.host();
            Outcome connected = await(CONNECT_WAIT, () -> {
                List<MdnsService> announced = mdnsServices().stream()
                        .filter(s -> s.kind() == MdnsKind.CONNECT && s.host().equals(host)).toList();
                List<ServerDevice> devices = devices();
                for (MdnsService service : announced) {
                    if (devices.stream().anyMatch(service::is)) {
                        return Optional.of(new Outcome(true, "Connected to " + service.displayName() + "."));
                    }
                }
                // Every announced port is tried, since the server's list can still hold an old one; a port can
                // also be announced a moment before it answers, so a refusal is asked again next second.
                for (MdnsService service : announced) {
                    Outcome outcome = connect(service.address());
                    if (outcome.ok()) return Optional.of(outcome);
                }
                return Optional.empty();
            });
            return connected != null ? connected : new Outcome(false, "Paired, but no connection to the phone "
                    + "within " + CONNECT_WAIT.toSeconds() + " seconds. Connect with the address on its Wireless "
                    + "debugging screen.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, "Stopped.");
        }
    }

    /** Asks {@code poll} every second until it answers or {@code wait} has passed; {@code null} then. */
    private static <T> T await(Duration wait, Supplier<Optional<T>> poll) throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Optional<T> answer = poll.get();
            if (answer.isPresent()) return answer.get();
            if (System.nanoTime() > deadline) return null;
            Thread.sleep(1_000);
        }
    }

    /** Runs one adb subcommand and decides success by what it said, since adb's exit status does not say. */
    private static Outcome run(List<String> arguments, String successMarker) {
        Optional<File> adb = binary();
        if (adb.isEmpty()) {
            return new Outcome(false, installHint());
        }
        List<String> command = new ArrayList<>();
        command.add(adb.get().getAbsolutePath());
        command.addAll(arguments);
        try {
            Spawn.Completed completed = Spawn.run(COMMAND_TIMEOUT, command);
            if (completed == null) {
                return new Outcome(false, "adb " + arguments.get(0) + " did not answer within "
                        + COMMAND_TIMEOUT.toSeconds() + "s");
            }
            String message = completed.output().trim();
            // The daemon's own start-up chatter arrives on the same merged stream; the verdict is the last line.
            String verdict = message.isEmpty() ? "" : message.substring(message.lastIndexOf('\n') + 1).trim();
            return new Outcome(message.contains(successMarker),
                    verdict.isEmpty() ? "adb said nothing" : verdict);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, "interrupted");
        } catch (Exception e) {
            return new Outcome(false, String.valueOf(e.getMessage()));
        }
    }

    /**
     * The one-line, user-facing sentence for a machine that cannot reach cabled or TLS-wireless devices.
     *
     * <p>It no longer sends the user to find platform-tools themselves: {@link ManagedTools#installPlatformTools}
     * fetches Google's own pinned build, so the missing piece is a click rather than an errand.
     */
    public static String installHint() {
        return "Android platform-tools (the `adb` command) is needed for a phone over USB or Android 11+ "
                + "wireless debugging, and BotMaker can download it for you — a phone already in `adb tcpip` "
                + "mode needs none of this and can be added by address";
    }
}
