package com.botmaker.shared.vm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * QEMU's machine protocol (QMP) over a loopback socket: one JSON object a line each way. It reads past the
 * events QEMU sends unasked, and an {@code error} reply is an {@link IOException} carrying QEMU's own sentence.
 * One command at a time.
 */
public final class QmpClient implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final ObjectMapper JSON = new ObjectMapper();

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

    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;

    private QmpClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = socket.getOutputStream();
    }

    /** Connects, reads QEMU's greeting and enters command mode. */
    public static QmpClient connect(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            QmpClient qmp = new QmpClient(socket);
            if (!qmp.read().has("QMP")) throw new IOException("Port " + port + " doesn't speak QMP.");
            qmp.execute("qmp_capabilities", Map.of());
            return qmp;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** Runs {@code command} with {@code arguments} and returns its {@code return} value. */
    public synchronized JsonNode execute(String command, Map<String, ?> arguments) throws IOException {
        ObjectNode request = JSON.createObjectNode().put("execute", command);
        if (!arguments.isEmpty()) request.set("arguments", JSON.valueToTree(arguments));
        out.write((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        while (true) {
            JsonNode reply = read();
            if (reply.has("event")) continue;
            if (reply.has("error")) {
                throw new Refused("QEMU refused " + command + ": " + reply.path("error").path("desc").asText());
            }
            if (reply.has("return")) return reply.get("return");
        }
    }

    /** Sets the password VNC clients must give. QEMU keeps only its first 8 characters, as VNC does. */
    public void setVncPassword(String password) throws IOException {
        execute("change-vnc-password", Map.of("password", password));
    }

    public RunState status() throws IOException {
        return RunState.fromId(execute("query-status", Map.of()).path("status").asText());
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

    private JsonNode read() throws IOException {
        String line = in.readLine();
        if (line == null) throw new IOException("QEMU closed its QMP connection.");
        return JSON.readTree(line);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
