package com.botmaker.shared.emulator;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * <b>Where an Android surface's {@code adbd} is</b>, as a closed set of the two shapes that exist — a TCP
 * address we dial ourselves, or a serial reached through a host adb server.
 *
 * <p>This replaces the {@code host} + {@code adbPort} pair {@link EmulatorInstance} used to carry, and it is
 * the "type a closed set instead of passing a bare String" rule doing real work rather than tidying. Every
 * emulator this stack knew about listened on a TCP port, so a pair of fields was the whole truth. A
 * <b>physical phone on a USB cable</b> is not reachable that way at all: {@code adbd} speaks the ADB protocol
 * over a USB endpoint, and the only thing that can hand it to a JVM is an adb server, which addresses it by
 * <em>serial</em>. Expressed as a host and a port, such a device can only be written down as {@code ""} and
 * {@code 0} — and every consumer's socket probe would then answer "not running" for a phone that is plugged
 * in and working. That is a silent wrong answer, which is exactly what a closed set is for.
 *
 * <p><b>The reachability probe lives here for the same reason.</b> It is the question that has a different
 * answer per variant — a TCP connect means nothing for a serial — and it had already been written twice
 * against the old pair ({@code EmulatorReadiness.portOpen} and the SDK's {@code EmulatorRef.running}), which
 * is the duplication the repo's "a shared type owns the probes its consumers would otherwise each rebuild"
 * note is about.
 *
 * @see AdbDevice#connect(AdbEndpoint)
 */
public sealed interface AdbEndpoint permits AdbEndpoint.Tcp, AdbEndpoint.Server {

    /** Connect timeout for the TCP probe — cheaper than an ADB handshake, and run in poll loops and pickers. */
    int PROBE_TIMEOUT_MS = 300;

    /**
     * The stable, human-readable address — {@code "127.0.0.1:5555"} or the device serial. Safe to persist and
     * safe to log, and it is what {@link EmulatorInstance#identity()} keys on, so it must be unique per device.
     */
    String label();

    /**
     * Whether something is answering here <em>right now</em>. Cheap enough for every row of a picker, and the
     * right question for a running/stopped dot — but not for "can I drive it", which is
     * {@link EmulatorReadiness#isReady}. Best-effort and total: never throws.
     */
    boolean reachable();

    /**
     * Whether bytes read from here stay inside this machine — no NIC, no cable, no radio.
     *
     * <p>This exists because <b>the cheapest screen grab is not the same one at both ends of that answer</b>.
     * A raw {@code screencap} skips the device-side PNG encode but moves the whole framebuffer: 1080×2400×4 is
     * 10.4 MB. On loopback that is free and the encode was the only cost, so raw wins outright. Over a USB
     * cable (~30 MB/s in practice) those same 10.4 MB take longer than the encode they saved, and over Wi-Fi
     * they are not close. So {@link AdbDevice#screencap()} reads this, rather than preferring one path
     * everywhere and making phones slower in the name of making emulators faster.
     *
     * <p>Conservative on purpose: only genuine loopback answers true. A Waydroid container on a {@code veth}
     * pair at {@code 192.168.240.x} is local in every way that matters here and still answers false — the cost
     * of that is one PNG encode, whereas guessing wrong the other way is a 10 MB transfer over a radio.
     */
    boolean local();

    /** An {@code adbd} listening on a TCP port: every desktop emulator, and a phone in {@code adb tcpip} mode. */
    record Tcp(String host, int port) implements AdbEndpoint {

        public Tcp {
            host = host == null ? "" : host.trim();
        }

        @Override
        public String label() {
            return host + ":" + port;
        }

        @Override
        public boolean reachable() {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Textual, deliberately — resolving the name would turn a per-frame question into a DNS lookup. */
        @Override
        public boolean local() {
            return "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host)
                    || host.startsWith("127.");
        }
    }

    /**
     * A device the host's adb server owns, addressed by serial — the only way to reach a phone over USB, and
     * the way Android 11+ TLS "Wireless debugging" is reached too (dadb speaks no STLS, the adb binary does).
     *
     * <p>Note the serial is <em>also</em> an {@code ip:port} string for a wirelessly-connected device; that is
     * the adb server's own naming and is passed through unchanged, so {@link #label()} stays the thing a user
     * would recognise.
     */
    record Server(String serial) implements AdbEndpoint {

        public Server {
            serial = serial == null ? "" : serial.trim();
        }

        @Override
        public String label() {
            return serial;
        }

        /** Whether the adb server currently lists this serial as an online device. */
        @Override
        public boolean reachable() {
            for (AdbTools.ServerDevice device : AdbTools.devices()) {
                if (device.serial().equals(serial)) {
                    return device.online();
                }
            }
            return false;
        }

        /**
         * False, always. A serial is either a USB cable or a wireless device the adb server holds — never a
         * path that skips the wire, whatever the serial happens to spell.
         */
        @Override
        public boolean local() {
            return false;
        }
    }

    /** Shorthand for the overwhelmingly common case: an {@code adbd} on loopback. */
    static AdbEndpoint loopback(int port) {
        return new Tcp("127.0.0.1", port);
    }
}
