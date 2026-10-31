package com.botmaker.shared.emulator.tree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TreeReaders} against a fake device: the shell answers like Android's, and {@code forward} starts a local
 * HTTP server standing in for the {@code u2.jar} one, so the real JSON-RPC client is what reads.
 */
class TreeReadersTest {

    private static final String U2_XML =
            "<hierarchy rotation=\"0\"><node text=\"from u2\" package=\"p\" bounds=\"[0,0][9,9]\"/></hierarchy>";
    private static final String DUMP_XML = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
            + "<hierarchy rotation=\"0\"><node text=\"from dump\" package=\"p\" bounds=\"[0,0][9,9]\"/></hierarchy>";

    /** A device whose shell and forward behave enough like Android's for the reader. */
    static class FakeDevice implements TreeDevice {
        final List<String> commands = new ArrayList<>();
        boolean pushable = true;
        int pushes;
        int forwards;
        int forwardsClosed;
        String deviceSha = "";
        volatile boolean alive;
        /** The server answers 500 to everything, as a u2 that lost its UiAutomation does. */
        volatile boolean broken;
        /** Answers gzipped when asked, as u2.jar does. */
        boolean gzip = true;

        @Override
        public synchronized String shell(String command) {
            commands.add(command);
            if (command.startsWith("sha256sum")) return deviceSha.isEmpty() ? "" : deviceSha + "  " + U2Reader.REMOTE_JAR;
            if (command.contains("app_process")) {
                alive = true;
                return "4242\n";
            }
            if (command.startsWith("kill ") || command.startsWith("pkill ")) {
                alive = false;
                return "";
            }
            if (command.contains("uiautomator dump")) return DUMP_XML + "UI hierchary dumped to: /dev/tty\n";
            return "";
        }

        @Override
        public boolean push(File file, String remotePath) {
            if (!pushable || !file.isFile()) return false;
            pushes++;
            deviceSha = U2Reader.JAR_SHA256;
            return true;
        }

        @Override
        public Forward forward(int devicePort) throws IOException {
            assertEquals(U2Reader.DEVICE_PORT, devicePort);
            forwards++;
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jsonrpc/0", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = new ObjectMapper().writeValueAsBytes(
                        Map.of("jsonrpc", "2.0", "id", 1, "result", U2_XML));
                int status = alive && !broken ? 200 : 500;
                if (gzip && "gzip".equals(exchange.getRequestHeaders().getFirst("Accept-Encoding"))) {
                    ByteArrayOutputStream zipped = new ByteArrayOutputStream();
                    try (GZIPOutputStream out = new GZIPOutputStream(zipped)) {
                        out.write(body);
                    }
                    body = zipped.toByteArray();
                    exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                }
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return new Forward() {
                @Override
                public int port() {
                    return server.getAddress().getPort();
                }

                @Override
                public void close() {
                    forwardsClosed++;
                    server.stop(0);
                }
            };
        }

        long starts() {
            return commands.stream().filter(c -> c.contains("app_process")).count();
        }

        long dumps() {
            return commands.stream().filter(c -> c.contains("uiautomator dump")).count();
        }
    }

    private static String text(Optional<UiTree> tree) {
        return tree.orElseThrow().roots().getFirst().text();
    }

    @Test
    void u2StartsOnceAndReadsOverRpc() {
        FakeDevice device = new FakeDevice();
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            for (int i = 0; i < 3; i++) assertEquals("from u2", text(reader.read()));
        }
        assertEquals(1, device.pushes);
        assertEquals(1, device.starts());
        assertEquals(1, device.forwards);
        assertEquals(0, device.dumps());
    }

    @Test
    void anAnswerNotGzippedIsReadToo() {
        FakeDevice device = new FakeDevice();
        device.gzip = false;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
        }
    }

    @Test
    void aJarAlreadyOnTheDeviceIsNotPushedAgain() {
        FakeDevice device = new FakeDevice();
        device.deviceSha = U2Reader.JAR_SHA256;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
        }
        assertEquals(0, device.pushes);
    }

    @Test
    void deadU2RestartsOnceThenFallsBackToDump() {
        FakeDevice device = new FakeDevice();
        try (TreeReader reader = TreeReaders.open(device, Duration.ofMillis(400))) {
            assertEquals("from u2", text(reader.read()));
            device.broken = true;
            assertEquals("from dump", text(reader.read()), "restarted once, still dead: the dump answers");
            assertEquals(2, device.starts());
            assertEquals("from dump", text(reader.read()));
            assertEquals(2, device.starts(), "no third start");
            assertEquals(2, device.dumps());
            assertEquals(device.forwards, device.forwardsClosed, "every forward of a given-up u2 is removed");
            assertTrue(device.commands.contains("kill 4242"));
            assertTrue(device.commands.contains("pkill -f com.wetest.uia2.Main"),
                    "a u2 nobody can stop would keep UiAutomation from the dump");
        }
    }

    @Test
    void aShellThatStopsAnsweringIsNoThrow() {
        FakeDevice device = new FakeDevice() {
            @Override
            public synchronized String shell(String command) {
                if (command.contains("app_process")) throw new RuntimeException("shell failed");
                return super.shell(command);
            }
        };
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from dump", text(reader.read()));
            assertEquals(device.forwards, device.forwardsClosed);
        }
    }

    @Test
    void thePidIsTheLastNumberEchoed() {
        assertEquals("4242", U2Reader.lastNumber("[1] 4242\n"));
        assertEquals("4242", U2Reader.lastNumber("4242\r\n"));
        assertEquals(null, U2Reader.lastNumber(""));
    }

    @Test
    void aU2ThatComesBackAfterItsRestartKeepsReading() {
        FakeDevice device = new FakeDevice();
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
            device.alive = false;
            assertEquals("from u2", text(reader.read()), "the restart brings it back");
            assertEquals(2, device.starts());
            assertEquals(0, device.dumps());
        }
    }

    @Test
    void aJarThatCannotBePushedReadsByDump() {
        FakeDevice device = new FakeDevice();
        device.pushable = false;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from dump", text(reader.read()));
        }
        assertEquals(0, device.starts());
        assertEquals(device.forwards, device.forwardsClosed, "the forward that found no server is removed");
    }

    @Test
    void aServerAlreadyThereIsUsedAndNotStopped() {
        FakeDevice device = new FakeDevice();
        device.alive = true;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
        }
        assertEquals(0, device.starts());
        assertTrue(device.commands.stream().noneMatch(c -> c.startsWith("kill")), "it is not this reader's");
        assertEquals(1, device.forwardsClosed);
    }

    @Test
    void closeKillsProcessAndRemovesForward() {
        FakeDevice device = new FakeDevice();
        TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5));
        assertEquals("from u2", text(reader.read()));
        reader.close();
        assertTrue(device.commands.contains("kill 4242"));
        assertEquals(1, device.forwardsClosed);
        assertTrue(reader.read().isEmpty(), "a closed reader reads nothing");
        reader.close();
        assertEquals(1, device.forwardsClosed, "closing twice is harmless");
    }

    @Test
    void openingStartsNothing() {
        FakeDevice device = new FakeDevice();
        TreeReaders.open(device, Duration.ofSeconds(5)).close();
        assertTrue(device.commands.isEmpty(), "u2 starts on the first read, and a reader never read starts nothing");
    }

    @Test
    void bundledJarMatchesItsPin() throws Exception {
        try (InputStream in = U2Reader.class.getResourceAsStream(U2Reader.RESOURCE)) {
            byte[] bytes = in.readAllBytes();
            assertEquals(U2Reader.JAR_SHA256,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        assertTrue(U2Reader.class.getResource(U2Reader.RESOURCE.replace("u2.jar", "u2.jar.NOTICE")) != null);
    }

    @Test
    void dumpStripsItsStatusLine() {
        FakeDevice device = new FakeDevice();
        assertEquals("from dump", text(new DumpReader(device).read()));
        assertEquals(List.of("uiautomator dump /dev/tty"), device.commands);
    }
}
