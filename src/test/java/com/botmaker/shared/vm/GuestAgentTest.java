package com.botmaker.shared.vm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guest agent against a fake one that keeps files in memory, as QEMU's chardev would relay it. */
class GuestAgentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A fake agent: answers guest-sync (after a stale reply), and opens only {@code existing} paths for reading. */
    private static final class FakeAgent implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final List<String> received = new CopyOnWriteArrayList<>();
        final List<byte[]> written = new CopyOnWriteArrayList<>();

        FakeAgent(Set<String> existing, boolean silent) throws IOException {
            Thread.ofPlatform().daemon().start(() -> {
                try (Socket s = listener.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    String line;
                    while ((line = in.readLine()) != null) {
                        received.add(line);
                        if (silent) continue;
                        JsonNode request = JSON.readTree(line);
                        JsonNode args = request.path("arguments");
                        String reply = switch (request.path("execute").asText()) {
                            case "guest-sync" -> "{\"return\": 7}\n{\"return\": " + args.path("id").asLong() + "}";
                            case "guest-file-open" -> args.path("mode").asText().equals("r")
                                    && !existing.contains(args.path("path").asText())
                                    ? "{\"error\": {\"class\": \"GenericError\", \"desc\": \"failed to open file\"}}"
                                    : "{\"return\": 1000}";
                            case "guest-file-write" -> {
                                byte[] chunk = Base64.getDecoder().decode(args.path("buf-b64").asText());
                                written.add(chunk);
                                yield "{\"return\": {\"count\": " + chunk.length + ", \"eof\": false}}";
                            }
                            case "guest-exec" -> "{\"return\": {\"pid\": 4242}}";
                            default -> "{\"return\": {}}";
                        };
                        out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException e) {
                    // the test is over
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    @Test
    void itSkipsAStaleReplyThenReadsWritesAndRuns() throws Exception {
        try (FakeAgent fake = new FakeAgent(Set.of("C:\\BotMaker\\ready"), false);
             GuestAgent agent = GuestAgent.connect(fake.port(), 3_000)) {
            assertTrue(agent.fileExists("C:\\BotMaker\\ready"));
            assertFalse(agent.fileExists("C:\\nothing"));

            byte[] big = new byte[100_000];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 7);
            agent.writeFile(GuestUnattend.LAUNCH_SCRIPT, big);
            assertEquals(3, fake.written.size(), "in 48 KB chunks");
            List<Byte> joined = new ArrayList<>();
            for (byte[] chunk : fake.written) for (byte b : chunk) joined.add(b);
            byte[] back = new byte[joined.size()];
            for (int i = 0; i < back.length; i++) back[i] = joined.get(i);
            assertArrayEquals(big, back);

            agent.runLaunchTask();
            JsonNode exec = JSON.readTree(fake.received.getLast());
            assertEquals("schtasks.exe", exec.path("arguments").path("path").asText());
            assertEquals("[\"/Run\",\"/TN\",\"BotMaker launch\"]", exec.path("arguments").path("arg").toString());
        }
    }

    @Test
    void noAgentInTheGuestIsNoAnswerWithinTheTimeout() throws Exception {
        try (FakeAgent fake = new FakeAgent(Set.of(), true)) {
            long start = System.nanoTime();
            assertFalse(GuestAgent.answers(fake.port(), 500));
            assertTrue(System.nanoTime() - start < 3_000_000_000L);
        }
    }
}
