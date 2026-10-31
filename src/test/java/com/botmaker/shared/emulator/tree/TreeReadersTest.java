package com.botmaker.shared.emulator.tree;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TreeReaders} against a fake device: the shell answers like Android's, and {@code exec} runs a fake
 * {@code TreeStdio} on pipes, speaking its protocol, so the real client is what reads.
 */
class TreeReadersTest {

    private static final String U2_XML =
            "<hierarchy rotation=\"0\"><node text=\"from u2\" package=\"p\" bounds=\"[0,0][9,9]\"/></hierarchy>";
    private static final String DUMP_XML = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
            + "<hierarchy rotation=\"0\"><node text=\"from dump\" package=\"p\" bounds=\"[0,0][9,9]\"/></hierarchy>";

    /** A device whose shell and processes behave enough like Android's for the reader. */
    static class FakeDevice implements TreeDevice {
        final List<String> commands = new ArrayList<>();
        final Map<String, String> deviceSha = new HashMap<>();
        boolean pushable = true;
        int pushes;
        int execs;
        int channelsClosed;
        /** Answers an empty frame, as a TreeStdio whose dump failed does. */
        volatile boolean broken;
        /** How many empty frames come before the trees. */
        volatile int emptyFrames;
        /** The next process ends after this many trees, as one killed does; -1 never. */
        volatile int framesBeforeDying = -1;
        /** What the process prints before its ready line. */
        String noise = "";
        boolean saysReady = true;
        /** Counts down when a process sees its stdin end. */
        volatile CountDownLatch ended = new CountDownLatch(1);

        @Override
        public synchronized String shell(String command) {
            commands.add(command);
            if (command.startsWith("sha256sum")) {
                String remote = command.split(" ")[1];
                String sha = deviceSha.get(remote);
                return sha == null ? "" : sha + "  " + remote;
            }
            if (command.contains("uiautomator dump")) return DUMP_XML + "UI hierchary dumped to: /dev/tty\n";
            return "";
        }

        @Override
        public boolean push(File file, String remotePath) {
            if (!pushable || !file.isFile()) return false;
            pushes++;
            deviceSha.put(remotePath, remotePath.equals(U2Reader.REMOTE_U2) ? U2Reader.U2_SHA256 : U2Reader.STDIO_SHA256);
            return true;
        }

        @Override
        public Channel exec(String command) throws IOException {
            commands.add(command);
            execs++;
            Pipe toProcess = Pipe.open();
            Pipe fromProcess = Pipe.open();
            InputStream processIn = Channels.newInputStream(toProcess.source());
            OutputStream processOut = Channels.newOutputStream(fromProcess.sink());
            int frames = framesBeforeDying;
            framesBeforeDying = -1;
            CountDownLatch myEnd = ended;
            Thread.ofVirtual().start(() -> run(processIn, processOut, frames, myEnd));
            InputStream in = Channels.newInputStream(fromProcess.source());
            OutputStream out = Channels.newOutputStream(toProcess.sink());
            return new Channel() {
                @Override
                public InputStream in() {
                    return in;
                }

                @Override
                public OutputStream out() {
                    return out;
                }

                @Override
                public void close() {
                    channelsClosed++;
                    try {
                        out.close();
                        in.close();
                    } catch (IOException ignored) {
                        // closed already
                    }
                }
            };
        }

        private void run(InputStream stdin, OutputStream stdout, int frames, CountDownLatch end) {
            try (DataOutputStream out = new DataOutputStream(stdout)) {
                out.write(noise.getBytes(StandardCharsets.US_ASCII));
                if (saysReady) out.write((U2Reader.READY + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                for (int b, sent = 0; frames < 0 || sent < frames; ) {
                    if ((b = stdin.read()) < 0) break;
                    if (b != '\n') continue;
                    sent++;
                    boolean empty = broken || emptyFrames-- > 0;
                    byte[] frame = empty ? new byte[0] : gzip(U2_XML);
                    out.writeInt(frame.length);
                    out.write(frame);
                    out.flush();
                }
                end.countDown();
            } catch (IOException gone) {
                end.countDown();
            }
        }

        long dumps() {
            return commands.stream().filter(c -> c.contains("uiautomator dump")).count();
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(zipped)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return zipped.toByteArray();
    }

    private static String text(Optional<UiTree> tree) {
        return tree.orElseThrow().roots().getFirst().text();
    }

    @Test
    void startsOnceAndReadsOverTheStream() {
        FakeDevice device = new FakeDevice();
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            for (int i = 0; i < 3; i++) assertEquals("from u2", text(reader.read()));
        }
        assertEquals(2, device.pushes, "u2.jar and tree-stdio.jar");
        assertEquals(1, device.execs);
        assertEquals(0, device.dumps());
        String start = device.commands.stream().filter(c -> c.contains("app_process")).findFirst().orElseThrow();
        assertTrue(start.contains(U2Reader.MAIN), "TreeStdio runs, never u2's own server");
        assertTrue(!start.contains("com.wetest.uia2.Main") && !start.contains("-p "), "no port is opened");
    }

    @Test
    void jarsAlreadyOnTheDeviceAreNotPushedAgain() {
        FakeDevice device = new FakeDevice();
        device.deviceSha.put(U2Reader.REMOTE_U2, U2Reader.U2_SHA256);
        device.deviceSha.put(U2Reader.REMOTE_STDIO, U2Reader.STDIO_SHA256);
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
        }
        assertEquals(0, device.pushes);
    }

    @Test
    void whatPrintsBeforeTheReadyLineIsSkipped() {
        FakeDevice device = new FakeDevice();
        device.noise = "WARNING: linker: something\r\nanother line\n";
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
        }
    }

    @Test
    void aProcessThatNeverSaysReadyFallsBackToDump() {
        FakeDevice device = new FakeDevice();
        device.saysReady = false;
        device.noise = "still starting\n";
        try (TreeReader reader = TreeReaders.open(device, Duration.ofMillis(300))) {
            assertEquals("from dump", text(reader.read()));
        }
        assertEquals(device.execs, device.channelsClosed, "the stream of a start that timed out is closed");
    }

    @Test
    void aBrokenReaderRestartsOnceThenFallsBackToDump() {
        FakeDevice device = new FakeDevice();
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
            device.broken = true;
            assertEquals("from dump", text(reader.read()), "restarted once, still broken: the dump answers");
            assertEquals(2, device.execs);
            assertEquals("from dump", text(reader.read()));
            assertEquals(2, device.execs, "no third start");
            assertEquals(2, device.dumps());
            assertEquals(device.execs, device.channelsClosed, "every stream of a given-up reader is closed");
            assertTrue(device.commands.stream().noneMatch(c -> c.contains("kill")),
                    "another reader's process, or a user's own uiautomator2, is not this reader's");
        }
    }

    @Test
    void oneEmptyFrameIsAskedAgainOnTheSameProcess() {
        FakeDevice device = new FakeDevice();
        device.emptyFrames = 2;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()), "a dump that failed mid-transition is no dead process");
        }
        assertEquals(1, device.execs);
    }

    @Test
    void theReadyLineIsTheOneTreeStdioPrints() throws IOException {
        String source = Files.readString(Path.of("src/android/java/com/botmaker/android/TreeStdio.java"));
        assertTrue(source.contains("READY = \"" + U2Reader.READY + "\""), "both sides of the protocol must agree");
    }

    @Test
    void aProcessThatNeverGaveATreeIsNotRestarted() {
        FakeDevice device = new FakeDevice();
        device.framesBeforeDying = 0;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from dump", text(reader.read()));
        }
        assertEquals(1, device.execs, "only a reader that worked is worth a restart");
    }

    @Test
    void aProcessThatDiesIsStartedAgainAndKeepsReading() {
        FakeDevice device = new FakeDevice();
        device.framesBeforeDying = 1;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from u2", text(reader.read()));
            assertEquals("from u2", text(reader.read()), "the restart answers");
            assertEquals("from u2", text(reader.read()));
        }
        assertEquals(2, device.execs);
        assertEquals(0, device.dumps());
    }

    @Test
    void aShellThatStopsAnsweringIsNoThrow() {
        FakeDevice device = new FakeDevice() {
            @Override
            public Channel exec(String command) {
                throw new RuntimeException("connection lost");
            }
        };
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from dump", text(reader.read()));
        }
    }

    @Test
    void aJarThatCannotBePushedReadsByDump() {
        FakeDevice device = new FakeDevice();
        device.pushable = false;
        try (TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5))) {
            assertEquals("from dump", text(reader.read()));
        }
        assertEquals(0, device.execs);
    }

    @Test
    void closeEndsTheProcess() throws InterruptedException {
        FakeDevice device = new FakeDevice();
        TreeReader reader = TreeReaders.open(device, Duration.ofSeconds(5));
        assertEquals("from u2", text(reader.read()));
        reader.close();
        assertTrue(device.ended.await(2, TimeUnit.SECONDS), "the process sees its stdin end, and ends");
        assertEquals(1, device.channelsClosed);
        assertTrue(reader.read().isEmpty(), "a closed reader reads nothing");
        reader.close();
        assertEquals(1, device.channelsClosed, "closing twice is harmless");
    }

    @Test
    void openingStartsNothing() {
        FakeDevice device = new FakeDevice();
        TreeReaders.open(device, Duration.ofSeconds(5)).close();
        assertTrue(device.commands.isEmpty(), "it starts on the first read, and a reader never read starts nothing");
    }

    @Test
    void bundledJarsMatchTheirPins() throws Exception {
        assertEquals(U2Reader.U2_SHA256, sha256(U2Reader.U2_RESOURCE));
        assertEquals(U2Reader.STDIO_SHA256, sha256(U2Reader.STDIO_RESOURCE), "rebuild with src/android/build.sh");
        assertNotNull(U2Reader.class.getResource(U2Reader.U2_RESOURCE + ".NOTICE"));
    }

    private static String sha256(String resource) throws Exception {
        try (InputStream in = U2Reader.class.getResourceAsStream(resource)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }

    @Test
    void aLineDropsItsCarriageReturnAndEndsAtEof() throws IOException {
        InputStream in = new ByteArrayInputStream("one\r\ntwo".getBytes(StandardCharsets.US_ASCII));
        assertEquals("one", U2Reader.line(in));
        IOException eof = null;
        try {
            U2Reader.line(in);
        } catch (IOException e) {
            eof = e;
        }
        assertNotNull(eof, "a line cut by the end is no line");
    }

    @Test
    void dumpStripsItsStatusLine() {
        FakeDevice device = new FakeDevice();
        assertEquals("from dump", text(new DumpReader(device).read()));
        assertEquals(List.of("uiautomator dump /dev/tty"), device.commands);
    }
}
