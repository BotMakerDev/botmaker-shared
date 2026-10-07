package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QMP against a fake QEMU that answers each command with a canned reply, events mixed in. */
class QmpClientTest {

    /** A fake QEMU: greets, then answers the n-th command with the n-th reply lines. */
    private static final class FakeQemu implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final List<String> received = new CopyOnWriteArrayList<>();

        FakeQemu(List<List<String>> replies) throws IOException {
            Thread.ofPlatform().daemon().start(() -> {
                try (Socket s = listener.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    send(out, "{\"QMP\": {\"version\": {}, \"capabilities\": []}}");
                    for (List<String> reply : replies) {
                        String line = in.readLine();
                        if (line == null) return;
                        received.add(line);
                        for (String r : reply) send(out, r);
                    }
                } catch (IOException e) {
                    // the test is over
                }
            });
        }

        private static void send(OutputStream out, String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    private static final String OK = "{\"return\": {}}";

    @Test
    void commandsGetTheirReplyPastTheEventsQemuSendsUnasked() throws Exception {
        try (FakeQemu qemu = new FakeQemu(List.of(
                List.of(OK),
                List.of("{\"event\": \"RESUME\", \"timestamp\": {}}", OK),
                List.of("{\"return\": {\"status\": \"running\", \"running\": true}}")));
             QmpClient qmp = QmpClient.connect(qemu.port())) {
            qmp.setVncPassword("abcd1234");
            assertEquals(QmpClient.RunState.RUNNING, qmp.status());
            assertEquals("{\"execute\":\"qmp_capabilities\"}", qemu.received.get(0));
            assertEquals("{\"execute\":\"change-vnc-password\",\"arguments\":{\"password\":\"abcd1234\"}}",
                    qemu.received.get(1));
            assertEquals("{\"execute\":\"query-status\"}", qemu.received.get(2));
        }
    }

    @Test
    void anErrorIsQemusOwnSentence() throws Exception {
        try (FakeQemu qemu = new FakeQemu(List.of(List.of(OK),
                List.of("{\"error\": {\"class\": \"GenericError\", \"desc\": \"No VNC display\"}}")));
             QmpClient qmp = QmpClient.connect(qemu.port())) {
            IOException e = assertThrows(QmpClient.Refused.class, () -> qmp.setVncPassword("x"));
            assertTrue(e.getMessage().endsWith("No VNC display"), e.getMessage());
        }
    }

    @Test
    void quitIsDoneWhenQemuHangsUpBeforeAnswering() throws Exception {
        try (FakeQemu qemu = new FakeQemu(List.of(List.of(OK), List.of()));
             QmpClient qmp = QmpClient.connect(qemu.port())) {
            qmp.quit();
        }
    }
}
