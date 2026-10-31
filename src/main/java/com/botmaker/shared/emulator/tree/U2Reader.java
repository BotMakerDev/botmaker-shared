package com.botmaker.shared.emulator.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Reads the tree through openatx's {@code u2.jar} (bundled, {@code u2.jar.NOTICE}), run on the device with
 * {@code app_process}: a UiAutomator server answering JSON-RPC, 30–60 ms a read where {@code uiautomator dump}
 * takes 2 s, and it does not wait for the screen to be idle. Nothing is installed: the jar sits in
 * {@code /data/local/tmp}, and the process stops on {@link #stop()}.
 *
 * <p>A server already answering on the device's port (another BotMaker on the same device, or one a crashed run
 * left) is used as it is, and not stopped: it is not this reader's.
 */
final class U2Reader {

    static final String RESOURCE = "/com/botmaker/shared/emulator/tree/u2.jar";
    /** openatx/android-uiautomator-server-jar release 0.4.1, the one the uiautomator2 Python package pins. */
    static final String JAR_SHA256 = "87c8ac9505970d42aa5922baa90114808fda6f47f9e5fbd4caa0f621c2c8d30d";
    static final String REMOTE_JAR = "/data/local/tmp/u2.jar";
    static final int DEVICE_PORT = 9008;

    private static final String REQUEST =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"dumpWindowHierarchy\",\"params\":[false,50]}";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TreeDevice device;
    private final Duration startTimeout;
    /** One for every reader: a client holds a selector thread until it is collected. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private TreeDevice.Forward forward;
    /** The process this reader started, or {@code null} when it uses one already there. */
    private String pid;

    U2Reader(TreeDevice device, Duration startTimeout) {
        this.device = device;
        this.startTimeout = startTimeout;
    }

    boolean running() {
        return forward != null;
    }

    /** Forwards a port, then uses the server already there or pushes the jar and starts one, and waits for it. */
    void start() throws IOException {
        forward = device.forward(DEVICE_PORT);
        try {
            read();
            return;
        } catch (IOException nobodyThere) {
            // start ours
        }
        if (!device.shell("sha256sum " + REMOTE_JAR + " 2>/dev/null").startsWith(JAR_SHA256)
                && !device.push(localJar().toFile(), REMOTE_JAR)) {
            throw new IOException("u2.jar could not be copied to the device");
        }
        pid = lastNumber(device.shell("CLASSPATH=" + REMOTE_JAR
                + " nohup app_process / com.wetest.uia2.Main -p " + DEVICE_PORT + " >/dev/null 2>&1 & echo $!"));
        long deadline = System.nanoTime() + startTimeout.toNanos();
        IOException last;
        do {
            try {
                read();
                return;
            } catch (IOException notYet) {
                last = notYet;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IOException("u2.jar did not answer within " + startTimeout.toMillis() + " ms", last);
    }

    /** One tree over JSON-RPC. */
    UiTree read() throws IOException {
        if (forward == null) throw new IOException("not started");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + forward.port() + "/jsonrpc/0"))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                // A tree is ~75 KB of XML and ~4 KB gzipped; over dadb's transport that is 70 ms against 20.
                .header("Accept-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofString(REQUEST))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (response.statusCode() != 200) throw new IOException("u2.jar answered " + response.statusCode());
        boolean gzip = response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip");
        JsonNode reply = gzip
                ? JSON.readTree(new GZIPInputStream(new ByteArrayInputStream(response.body())))
                : JSON.readTree(response.body());
        JsonNode result = reply.get("result");
        if (result == null || !result.isTextual()) throw new IOException("u2.jar answered no tree: " + reply.get("error"));
        try {
            return UiTree.parse(result.asText());
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Gives u2 up for the dump: stops it, and kills any u2 left on the device too, whoever started it, since a
     * server that stopped answering still holds UiAutomation and {@code uiautomator dump} would fail beside it.
     * Never throws.
     */
    void abandon() {
        stop();
        try {
            device.shell("pkill -f com.wetest.uia2.Main");
        } catch (RuntimeException ignored) {
            // the device is gone
        }
    }

    /** Kills the process this reader started and removes the forward. Never throws. */
    void stop() {
        if (pid != null) {
            try {
                device.shell("kill " + pid);
            } catch (RuntimeException ignored) {
                // the device is gone, and the process with it
            }
        }
        pid = null;
        if (forward != null) forward.close();
        forward = null;
    }

    /** The last number in {@code echo $!}'s output, past any job-control line ({@code [1] 4242}); null if none. */
    static String lastNumber(String out) {
        Matcher m = Pattern.compile("\\d+").matcher(out == null ? "" : out);
        String last = null;
        while (m.find()) last = m.group();
        return last;
    }

    private static void sleep() throws IOException {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static Path extracted;

    /** The bundled jar as a file, copied out once per JVM: a push needs a file. */
    private static synchronized Path localJar() throws IOException {
        if (extracted != null && Files.isRegularFile(extracted)) return extracted;
        try (InputStream in = U2Reader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("u2.jar is not bundled");
            Path file = Files.createTempFile("botmaker-u2-", ".jar");
            file.toFile().deleteOnExit();
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            extracted = file;
            return file;
        }
    }
}
