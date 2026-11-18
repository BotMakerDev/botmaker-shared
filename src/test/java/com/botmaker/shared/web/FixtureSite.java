// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fixture site of {@code src/test/resources/web/site/}, served on loopback by the JDK's {@link HttpServer}: no test
 * of the web target ever visits a real site. {@code /a} serves {@code a.html} or {@code a/index.html}; anything else
 * answers 404. Every request is counted.
 */
final class FixtureSite implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    FixtureSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            Optional<byte[]> page = page(path);
            byte[] body = page.orElse("<!doctype html><title>Not found</title><h1>Not found</h1>"
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(page.isPresent() ? 200 : 404,
                    exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (!exchange.getRequestMethod().equals("HEAD")) {
                    out.write(body);
                }
            }
        });
        server.start();
    }

    /** The site's root, {@code http://127.0.0.1:<port>}. */
    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String url(String path) {
        return base() + path;
    }

    int hits() {
        return hits.get();
    }

    private static Optional<byte[]> page(String path) throws IOException {
        if (path.contains("..")) {
            return Optional.empty();
        }
        String clean = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String candidate : new String[] {clean + ".html", clean + "/index.html"}) {
            try (InputStream in = FixtureSite.class.getResourceAsStream("/web/site" + candidate)) {
                if (in != null) {
                    return Optional.of(in.readAllBytes());
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
