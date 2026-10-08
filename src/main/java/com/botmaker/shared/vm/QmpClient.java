package com.botmaker.shared.vm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * QEMU's machine protocol (QMP) over a loopback socket: one JSON object a line each way. It reads past the
 * events QEMU sends unasked, and an {@code error} reply is a {@link Refused} carrying QEMU's own sentence.
 * One command at a time.
 */
public final class QmpClient implements AutoCloseable {

    private static final int READ_TIMEOUT_MS = 30_000;

    /** QEMU answered, and refused: its own sentence. Any other {@link IOException} is the connection's. */
    public static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    /** QEMU's run state, as {@code query-status} names it; the ones a game VM meets, the rest {@link #UNKNOWN}. */
    public enum RunState {
        RUNNING("running", "Running"),
        PAUSED("paused", "Paused"),
        SHUTDOWN("shutdown", "Shut down"),
        GUEST_PANICKED("guest-panicked", "Crashed"),
        INTERNAL_ERROR("internal-error", "Stopped on an error"),
        UNKNOWN("unknown", "Unknown");

        private final String id;
        private final String displayName;

        RunState(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static RunState fromId(String id) {
            for (RunState s : values()) {
                if (s.id.equals(id)) return s;
            }
            return UNKNOWN;
        }
    }

    private final JsonChannel channel;

    private QmpClient(JsonChannel channel) {
        this.channel = channel;
    }

    /** Connects, reads QEMU's greeting and enters command mode. */
    public static QmpClient connect(int port) throws IOException {
        JsonChannel channel = JsonChannel.connect(port, READ_TIMEOUT_MS);
        try {
            if (!channel.read().has("QMP")) throw new IOException("Port " + port + " doesn't speak QMP.");
            channel.execute("qmp_capabilities", Map.of());
            return new QmpClient(channel);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Whether something listens on {@code port}: for a VM's own QMP port, that its QEMU is running. Only a
     * connection, never a greeting: QEMU serves one QMP client at a time, and a second waits.
     */
    public static boolean listening(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs {@code command} with {@code arguments} and returns its {@code return} value. */
    public synchronized JsonNode execute(String command, Map<String, ?> arguments) throws IOException {
        return channel.execute(command, arguments);
    }

    /** Sets the password VNC clients must give. QEMU keeps only its first 8 characters, as VNC does. */
    public void setVncPassword(String password) throws IOException {
        execute("change-vnc-password", Map.of("password", password));
    }

    public RunState status() throws IOException {
        return RunState.fromId(execute("query-status", Map.of()).path("status").asText());
    }

    /** How many VNC clients are connected to the VM's screen now: a bot's session, a VM screen in Studio. */
    public int vncClients() throws IOException {
        return execute("query-vnc", Map.of()).path("clients").size();
    }

    /** Presses the guest's power button: Windows shuts down as it does for one. */
    public void powerDown() throws IOException {
        execute("system_powerdown", Map.of());
    }

    /**
     * Ends QEMU at once, as pulling the plug would. QEMU may hang up before it answers, however the socket
     * reports that, which is success too; only a refusal throws.
     */
    public void quit() throws IOException {
        try {
            execute("quit", Map.of());
        } catch (Refused e) {
            throw e;
        } catch (IOException e) {
            // gone, as asked
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
