package com.botmaker.shared.emulator.tree;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * Reads the tree with openatx's {@code u2.jar} (bundled, {@code u2.jar.NOTICE}): 30–60 ms a read where
 * {@code uiautomator dump} takes 2 s, and it does not wait for the screen to be idle. Nothing is installed: the
 * jars sit in {@code /data/local/tmp}.
 *
 * <p><b>No port.</b> u2's own server listens on every interface of the device with no password, and anyone who
 * reaches it (the Wi-Fi, any app on the device, a web page reaching an emulator's address) can tap, type and
 * read the screen. So u2's server is never started: BotMaker's own {@code TreeStdio} ({@code src/android},
 * bundled as {@code tree-stdio.jar}) builds u2's tree reader and answers on the stdin and stdout of the adb
 * stream that started it. Closing the stream ends the process, so nothing is left running, even when BotMaker
 * dies.
 */
final class U2Reader {

    static final String U2_RESOURCE = "/com/botmaker/shared/emulator/tree/u2.jar";
    /** openatx/android-uiautomator-server-jar release 0.4.1, the one the uiautomator2 Python package pins. */
    static final String U2_SHA256 = "87c8ac9505970d42aa5922baa90114808fda6f47f9e5fbd4caa0f621c2c8d30d";
    /** Not uiautomator2's own {@code u2.jar} path: a Python user's copy is neither overwritten nor counted on. */
    static final String REMOTE_U2 = "/data/local/tmp/botmaker-u2.jar";
    static final String STDIO_RESOURCE = "/com/botmaker/shared/emulator/tree/tree-stdio.jar";
    /** {@code src/android/build.sh}'s output; it prints the new value. */
    static final String STDIO_SHA256 = "8fc09eda414b171cd0d7541470d0e3341e77cdc44fcf4499991325741f172eb5";
    static final String REMOTE_STDIO = "/data/local/tmp/botmaker-tree-stdio.jar";
    static final String MAIN = "com.botmaker.android.TreeStdio";
    static final String READY = "BOTMAKER-TREE 1";

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** Lines skipped before {@link #READY}, should anything print first. */
    private static final int MAX_NOISE_LINES = 64;
    private static final int MAX_FRAME = 64 << 20;
    private static final int EMPTY_RETRIES = 3;

    private final TreeDevice device;
    private final Duration startTimeout;

    private TreeDevice.Channel channel;
    private DataInputStream in;
    private OutputStream out;

    U2Reader(TreeDevice device, Duration startTimeout) {
        this.device = device;
        this.startTimeout = startTimeout;
    }

    boolean running() {
        return channel != null;
    }

    /** Copies the jars that differ, starts {@code TreeStdio} and waits for its ready line. */
    void start() throws IOException {
        install(U2_RESOURCE, U2_SHA256, REMOTE_U2);
        install(STDIO_RESOURCE, STDIO_SHA256, REMOTE_STDIO);
        channel = device.exec("CLASSPATH=" + REMOTE_U2 + ":" + REMOTE_STDIO + " app_process / " + MAIN + " 2>/dev/null");
        DataInputStream from = new DataInputStream(new BufferedInputStream(channel.in()));
        in = from;
        out = channel.out();
        timed(startTimeout, () -> {
            for (int i = 0; i < MAX_NOISE_LINES; i++) {
                if (line(from).equals(READY)) return null;
            }
            throw new IOException("TreeStdio never said it was ready");
        });
    }

    /**
     * One tree: a line asks, a gzipped frame answers. An empty frame is a dump that failed (windows changing
     * mid-read), asked again on the same process before the read fails.
     */
    UiTree read() throws IOException {
        if (channel == null) throw new IOException("not started");
        DataInputStream from = in;
        OutputStream to = out;
        return timed(READ_TIMEOUT, () -> {
            int length = 0;
            for (int ask = 0; ask < EMPTY_RETRIES && length == 0; ask++) {
                to.write('\n');
                to.flush();
                length = from.readInt();
            }
            if (length <= 0 || length > MAX_FRAME) throw new IOException("TreeStdio answered no tree");
            byte[] frame = from.readNBytes(length);
            if (frame.length < length) throw new EOFException("TreeStdio ended mid-tree");
            String xml;
            try (InputStream gz = new GZIPInputStream(new ByteArrayInputStream(frame))) {
                xml = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
            try {
                return UiTree.parse(xml);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
        });
    }

    /**
     * Closes the stream, which ends {@code TreeStdio}. Never throws. Nothing else on the device is killed: another
     * reader's {@code TreeStdio} (Studio's picker beside a running bot) or a user's own uiautomator2 is not this
     * reader's, and each ends with its own stream.
     */
    void stop() {
        if (channel != null) channel.close();
        channel = null;
        in = null;
        out = null;
    }

    /**
     * Runs {@code work} with a deadline; past it the stream is closed, which ends a blocked read. A read on an
     * adb stream has no timeout of its own.
     */
    private <T> T timed(Duration timeout, Callable<T> work) throws IOException {
        FutureTask<T> task = new FutureTask<>(work);
        Thread.ofVirtual().name("tree-read").start(task);
        try {
            return await(task, timeout);
        } catch (IOException e) {
            stop();
            throw e;
        }
    }

    private static <T> T await(Future<T> task, Duration timeout) throws IOException {
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new IOException("TreeStdio did not answer within " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(String.valueOf(e.getCause()), e.getCause());
        }
    }

    /** One ASCII line, without its end; EOF before a line is an error. */
    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != '\n'; ) {
            if (b < 0) throw new EOFException("TreeStdio ended");
            if (bytes.size() < 4096 && b != '\r') bytes.write(b);
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private void install(String resource, String sha256, String remote) throws IOException {
        if (device.shell("sha256sum " + remote + " 2>/dev/null").startsWith(sha256)) return;
        if (!device.push(localJar(resource).toFile(), remote)) {
            throw new IOException(remote + " could not be copied to the device");
        }
    }

    private static final Map<String, Path> EXTRACTED = new HashMap<>();

    /** A bundled jar as a file, copied out once per JVM: a push needs a file. */
    private static synchronized Path localJar(String resource) throws IOException {
        Path known = EXTRACTED.get(resource);
        if (known != null && Files.isRegularFile(known)) return known;
        try (InputStream in = U2Reader.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException(resource + " is not bundled");
            Path file = Files.createTempFile("botmaker-tree-", ".jar");
            file.toFile().deleteOnExit();
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            EXTRACTED.put(resource, file);
            return file;
        }
    }
}
