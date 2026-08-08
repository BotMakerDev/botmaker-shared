package com.botmaker.shared.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * <b>Fetching one pinned file, and refusing anything that isn't it.</b> The JDK's own {@link HttpClient} and
 * {@link MessageDigest} do the work — shared takes no new dependency for this.
 *
 * <p>The contract is the point: a download either lands complete and matching its pin, or it lands nowhere.
 * The bytes go to a {@code .part} file beside the target and are only moved into place after the digest
 * agrees, so a half-finished or wrong download can never be mistaken for an installed tool. That matters more
 * here than in most downloaders, because what is being fetched is a binary this stack then <em>executes</em>
 * — {@code adb} on the host, {@code scrcpy-server} on the user's phone.
 *
 * <p>Nothing throws. Every failure — no network, a 404, a truncated body, a digest that doesn't match — is
 * {@code false}, because every caller has the same answer to all of them: say so, and carry on without the
 * tool. That is the same shape as {@code AdbTools} and {@code ScrcpyServer}, whose whole design is that the
 * absence of an optional capability is an ordinary state rather than an error.
 */
public final class Downloads {

    private Downloads() {}

    /** How long to wait for a server to answer at all. The body itself is then read for as long as it takes. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * The digest algorithms a pin may use, with the hex length that proves a constant is well-formed.
     *
     * <p>Typed rather than passed as a {@code String} because both halves must agree: {@code "SHA-1"} with a
     * 64-character digest is a pin that can never match, and a typo in either would only be discovered by a
     * download that mysteriously always fails. {@code SHA_1} exists because Google publishes only SHA-1 in
     * {@code repository2-3.xml} — it is a weak hash, and it is doing integrity work here on top of TLS, not
     * standing alone as authentication.
     */
    public enum Digest {
        SHA_256("SHA-256", 64),
        SHA_1("SHA-1", 40);

        private final String algorithm;
        private final int hexLength;

        Digest(String algorithm, int hexLength) {
            this.algorithm = algorithm;
            this.hexLength = hexLength;
        }

        public String algorithm() {
            return algorithm;
        }

        public int hexLength() {
            return hexLength;
        }
    }

    /**
     * One pinned file: where it is, and what it must turn out to be.
     *
     * @param url    an absolute URL
     * @param digest which algorithm {@code hex} is in
     * @param hex    the expected digest, case-insensitive
     * @param size   the expected byte count, or {@code -1} if unpinned. Checked before the digest so a
     *               truncated body is reported as what it is rather than as a hash mismatch.
     */
    public record Remote(String url, Digest digest, String hex, long size) {

        /**
         * Whether this pin is even self-consistent: an absolute URL, and a digest of the right length and
         * alphabet for its algorithm. A bad constant then fails a test rather than a user's download.
         *
         * <p>It deliberately does <b>not</b> require {@code https} — that is asserted on the real pins in
         * {@code ManagedToolsTest}, where it belongs, so that the download itself stays testable against a
         * loopback server without either weakening the rule or standing up a certificate for it.
         */
        public boolean wellFormed() {
            return url != null && (url.startsWith("https://") || url.startsWith("http://"))
                    && hex != null && hex.length() == digest.hexLength()
                    && hex.chars().allMatch(c -> Character.digit(c, 16) >= 0);
        }

        /** Whether the bytes arrive over TLS. Every shipped pin must; see {@code ManagedTools}. */
        public boolean secure() {
            return url != null && url.startsWith("https://");
        }
    }

    /** Progress as it happens. {@code total} is {@code -1} when the server did not say how long the body is. */
    @FunctionalInterface
    public interface Progress {
        void accept(long bytes, long total);

        /** For a caller that only wants the file. */
        Progress IGNORED = (bytes, total) -> { };
    }

    /**
     * Fetches {@code remote} to {@code target}, replacing whatever was there, and returns whether the file now
     * at {@code target} is the pinned one.
     *
     * <p>Returns {@code true} immediately if the target already matches the pin: an install is idempotent, and
     * re-downloading 16 MB because a caller asked twice is not something a user would thank us for.
     */
    public static boolean fetch(Remote remote, Path target, Progress progress) {
        if (remote == null || target == null || !remote.wellFormed()) {
            return false;
        }
        if (matches(target, remote)) {
            return true;
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String actual = download(remote, part, progress == null ? Progress.IGNORED : progress);
            if (actual == null || !actual.equalsIgnoreCase(remote.hex())) {
                Files.deleteIfExists(part);
                return false;
            }
            move(part, target);
            return true;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(part);
            } catch (Exception ignored) {
                // The temp file is beside the target and named .part; a leftover one is never mistaken for it.
            }
            return false;
        }
    }

    /**
     * Streams the body to {@code part}, hashing as it goes, and returns the hex digest — or {@code null} when
     * the response was not a 200 or the body was not the pinned length.
     *
     * <p>Hashing during the copy rather than re-reading afterwards is not (only) an optimisation: it means the
     * bytes that were hashed are exactly the bytes that were written, with no window in between.
     */
    private static String download(Remote remote, Path part, Progress progress) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)   // GitHub releases redirect to a CDN host.
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(remote.url()))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                return null;
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1);
            MessageDigest digest = MessageDigest.getInstance(remote.digest().algorithm());
            long written = 0;
            byte[] buffer = new byte[BUFFER_BYTES];
            try (OutputStream out = Files.newOutputStream(part)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    written += read;
                    progress.accept(written, total);
                }
            }
            if (remote.size() > 0 && written != remote.size()) {
                return null;
            }
            return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        }
    }

    /** Whether the file already on disk is the pinned one — a cheap check that skips a whole download. */
    public static boolean matches(Path file, Remote remote) {
        if (file == null || remote == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (remote.size() > 0 && Files.size(file) != remote.size()) {
                return false;
            }
            return digestOf(file, remote.digest()).equalsIgnoreCase(remote.hex());
        } catch (Exception e) {
            return false;
        }
    }

    /** The file's digest as lowercase hex. */
    static String digestOf(Path file, Digest algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm.algorithm());
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    /** Atomic where the filesystem allows it, correct where it doesn't. */
    private static void move(Path from, Path to) throws Exception {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
