package com.botmaker.shared.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Serves one folder of this PC, read-only, to a QEMU guest while a game is copied into it: QEMU's user network
 * hands the guest's connections to {@code 10.0.2.2} to this PC's loopback, so the server listens on
 * {@code 127.0.0.1} alone and no other computer reaches it. Every request names a random token; {@code GET
 * /<token>/list} answers one {@code <size>\t<relative path>} line per file, and {@code GET
 * /<token>/file?p=<path>} a file of that list, nothing else.
 *
 * <p>Plain HTTP/1.0 over a socket: Studio's bundled runtime leaves out the JDK's own HTTP server, and a guest's
 * {@code WebClient} needs nothing more.
 */
final class FolderServer implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServerSocket socket;
    private final String token;
    /** Each file served, by its relative path with {@code \}, as the guest writes it, in the guest's order. */
    private final SortedMap<String, Path> files;
    /** Each file's size at the walk: what the list says, whatever the file does since. */
    private final Map<String, Long> sizes;
    private final long total;
    private final AtomicLong sent = new AtomicLong();

    private FolderServer(ServerSocket socket, String token, SortedMap<String, Path> files, Map<String, Long> sizes) {
        this.socket = socket;
        this.token = token;
        this.files = files;
        this.sizes = sizes;
        this.total = sizes.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Lists {@code folder}'s files and starts serving them on a free loopback port. */
    static FolderServer serve(Path folder) throws IOException {
        SortedMap<String, Path> files = new TreeMap<>();
        Map<String, Long> sizes = new HashMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String relative = folder.relativize(p).toString().replace('/', '\\');
                files.put(relative, p);
                sizes.put(relative, Files.size(p));
            }
        }
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        FolderServer server = new FolderServer(socket, HexFormat.of().formatHex(random),
                Collections.unmodifiableSortedMap(files), Map.copyOf(sizes));
        Thread.ofVirtual().name("folder-server-" + socket.getLocalPort()).start(server::accept);
        return server;
    }

    int port() {
        return socket.getLocalPort();
    }

    String token() {
        return token;
    }

    /** The bytes of every file listed. */
    long total() {
        return total;
    }

    /** The bytes of files sent so far: the copy's progress, a file sent twice counted twice. */
    long sent() {
        return sent.get();
    }

    /** The list {@code /list} answers, in the order the guest copies. */
    String list() {
        StringBuilder out = new StringBuilder();
        for (String relative : files.keySet()) out.append(sizes.get(relative)).append('\t').append(relative).append('\n');
        return out.toString();
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread.ofVirtual().start(() -> answer(client));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void answer(Socket client) {
        try (client; InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String request = reader.readLine();
            for (String header = reader.readLine(); header != null && !header.isEmpty(); header = reader.readLine()) {
                // headers: nothing in them matters here
            }
            String[] parts = request == null ? new String[0] : request.split(" ");
            String target = parts.length >= 2 && parts[0].equals("GET") ? parts[1] : "";
            String prefix = "/" + token + "/";
            if (!target.startsWith(prefix)) {
                status(out, "404 Not Found");
                return;
            }
            String what = target.substring(prefix.length());
            if (what.equals("list")) {
                byte[] body = list().getBytes(StandardCharsets.UTF_8);
                head(out, body.length);
                out.write(body);
                return;
            }
            Path file = what.startsWith("file?p=")
                    ? files.get(URLDecoder.decode(what.substring("file?p=".length()), StandardCharsets.UTF_8))
                    : null;
            if (file == null) {
                status(out, "404 Not Found");
                return;
            }
            InputStream body;
            long length;
            try {
                // Opened before the answer starts: a file this PC keeps locked (its game running) is refused whole,
                // not sent short.
                body = Files.newInputStream(file);
                length = Files.size(file);
            } catch (IOException e) {
                status(out, "409 Conflict");
                return;
            }
            try (body) {
                head(out, length);
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = body.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    sent.addAndGet(n);
                }
            }
        } catch (IOException e) {
            // the guest went away, or the file failed mid-way: its fetch fails and the guest retries it
        }
    }

    private static void head(OutputStream out, long length) throws IOException {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void status(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.0 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
