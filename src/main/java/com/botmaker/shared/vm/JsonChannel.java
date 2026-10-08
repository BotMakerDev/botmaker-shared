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
 * One JSON object a line each way over a loopback socket: how QEMU speaks both QMP and its guest agent. A
 * command's reply is the next {@code return} or {@code error}; events in between are skipped.
 */
final class JsonChannel implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final Socket socket;
    private final BufferedReader in;
    private final OutputStream out;
    private boolean broken;

    private JsonChannel(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = socket.getOutputStream();
    }

    static JsonChannel connect(int port, int readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(readTimeoutMs);
            return new JsonChannel(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** How long a read waits from now on; {@code 0} for as long as it takes. */
    void readTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    void send(String command, Map<String, ?> arguments) throws IOException {
        if (broken) throw new IOException("The connection to QEMU lost its place in a reply; connect again.");
        ObjectNode request = JSON.createObjectNode().put("execute", command);
        if (!arguments.isEmpty()) request.set("arguments", JSON.valueToTree(arguments));
        out.write((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** The next reply, past any events: its {@code return} value, or {@link QmpClient.Refused} for an error. */
    JsonNode reply(String command) throws IOException {
        while (true) {
            JsonNode reply = read();
            if (reply.has("event")) continue;
            if (reply.has("error")) {
                throw new QmpClient.Refused("QEMU refused " + command + ": " + reply.path("error").path("desc").asText());
            }
            if (reply.has("return")) return reply.get("return");
        }
    }

    JsonNode execute(String command, Map<String, ?> arguments) throws IOException {
        send(command, arguments);
        return reply(command);
    }

    /**
     * The next line. A failure here (a timeout, a hang-up) leaves a reply half-read or still coming, so the
     * channel refuses further commands rather than read that reply as theirs.
     */
    JsonNode read() throws IOException {
        try {
            String line = in.readLine();
            if (line == null) throw new IOException("QEMU closed the connection.");
            return JSON.readTree(line);
        } catch (IOException e) {
            broken = true;
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
