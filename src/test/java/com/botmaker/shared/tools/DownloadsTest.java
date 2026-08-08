package com.botmaker.shared.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The downloader, against a loopback HTTP server — no network, and nothing that depends on Google or GitHub
 * being up on the day the build runs.
 *
 * <p>What is worth asserting here is not "a file arrives": it is that a file only arrives when it is the
 * pinned one, since what gets downloaded is then executed on this machine and on the user's phone.
 */
class DownloadsTest {

    /** The body every test serves. */
    private static final String BODY = "botmaker-managed-tool-payload";

    /** A well-formed SHA-256 that is emphatically not {@link #BODY}'s — the "wrong pin" case. */
    private static final String WRONG_SHA256 =
            "cd8b21c1e0e1b2c0f1b9a8b3b7e14a9d1e7e6a2f8f6d2ba0e3b0a4c8e2b3f9a1";

    private HttpServer server;
    private String url;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/tool", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/missing", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        url = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Downloads.Remote pin(String hex, long size) {
        return new Downloads.Remote(url + "/tool", Downloads.Digest.SHA_256, hex, size);
    }

    private static String sha256OfBody() throws Exception {
        Path temp = Files.createTempFile("botmaker-digest", ".bin");
        try {
            Files.writeString(temp, BODY, StandardCharsets.UTF_8);
            return Downloads.digestOf(temp, Downloads.Digest.SHA_256);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    void aMatchingDownloadLands(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("nested").resolve("tool");
        assertTrue(Downloads.fetch(pin(sha256OfBody(), BODY.length()), target, Downloads.Progress.IGNORED));
        assertEquals(BODY, Files.readString(target, StandardCharsets.UTF_8));
    }

    /**
     * The one that matters: a wrong digest must leave <em>nothing</em> — not the file, and not the
     * {@code .part} it was streamed into. A leftover partial would be a file the next probe reports as
     * installed.
     */
    @Test
    void aWrongDigestLeavesNothingBehind(@TempDir Path dir) {
        Path target = dir.resolve("tool");
        assertFalse(Downloads.fetch(pin(WRONG_SHA256, BODY.length()), target, Downloads.Progress.IGNORED));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("tool.part")));
    }

    @Test
    void aWrongSizeIsRefusedBeforeTheDigestIsBelieved(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("tool");
        assertFalse(Downloads.fetch(pin(sha256OfBody(), BODY.length() + 1), target, Downloads.Progress.IGNORED));
        assertFalse(Files.exists(target));
    }

    @Test
    void aMissingUrlIsJustFalse(@TempDir Path dir) throws Exception {
        Downloads.Remote missing =
                new Downloads.Remote(url + "/missing", Downloads.Digest.SHA_256, sha256OfBody(), -1);
        assertFalse(Downloads.fetch(missing, dir.resolve("tool"), Downloads.Progress.IGNORED));
    }

    /** An install is idempotent: the second call must not spend the bytes again. */
    @Test
    void anAlreadyInstalledFileIsNotDownloadedTwice(@TempDir Path dir) throws Exception {
        Downloads.Remote remote = pin(sha256OfBody(), BODY.length());
        Path target = dir.resolve("tool");
        assertTrue(Downloads.fetch(remote, target, Downloads.Progress.IGNORED));
        int afterFirst = requests.get();
        assertTrue(Downloads.fetch(remote, target, Downloads.Progress.IGNORED));
        assertEquals(afterFirst, requests.get());
    }

    @Test
    void progressReportsBytesAgainstTheAdvertisedLength(@TempDir Path dir) throws Exception {
        AtomicLong last = new AtomicLong();
        AtomicLong total = new AtomicLong();
        assertTrue(Downloads.fetch(pin(sha256OfBody(), BODY.length()), dir.resolve("tool"),
                (bytes, length) -> {
                    last.set(bytes);
                    total.set(length);
                }));
        assertEquals(BODY.length(), last.get());
        assertEquals(BODY.length(), total.get());
    }

    @Test
    void aMalformedPinIsRefusedWithoutASocket(@TempDir Path dir) {
        assertFalse(new Downloads.Remote(url + "/tool", Downloads.Digest.SHA_256, "abc", -1).wellFormed());
        assertFalse(new Downloads.Remote("ftp://x/y", Downloads.Digest.SHA_1,
                "477254aa5f903c15cf51001717bdf347fb6b53e0", -1).wellFormed());
        assertFalse(Downloads.fetch(pin("not-hex", -1), dir.resolve("tool"), Downloads.Progress.IGNORED));
        assertEquals(0, requests.get());
    }
}
