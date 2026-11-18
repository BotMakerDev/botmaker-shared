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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fixture site of {@code src/test/resources/web/site/}, served on loopback by the JDK's {@link HttpServer}: no test
 * of the web target ever visits a real site. {@code /a} serves {@code a.html} or {@code a/index.html}; anything else
 * answers 404. {@code /robots.txt} serves {@link #robots(String)}, and {@code /redirect-out} redirects to
 * {@link #redirect(String)}. A page's {@code %SITE%} and {@code %OTHER%} are replaced by {@link #names}. Every request
 * is counted, by path too.
 */
final class FixtureSite implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final Map<String, AtomicInteger> byPath = new ConcurrentHashMap<>();
    private volatile String robots;
    private volatile String redirect = "";
    private volatile Map<String, String> names = Map.of();

    FixtureSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            byPath.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                writes.incrementAndGet();
            }
            if (path.equals("/redirect-out")) {
                exchange.getResponseHeaders().set("Location", redirect);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            Optional<byte[]> page = path.equals("/robots.txt")
                    ? Optional.ofNullable(robots).map(r -> r.getBytes(StandardCharsets.UTF_8))
                    : page(path).map(this::named);
            byte[] body = page.orElse("<!doctype html><title>Not found</title><h1>Not found</h1>"
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type",
                    path.equals("/robots.txt") ? "text/plain" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(page.isPresent() ? 200 : 404, method.equals("HEAD") ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (!method.equals("HEAD")) {
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

    int port() {
        return server.getAddress().getPort();
    }

    int hits() {
        return hits.get();
    }

    /** Requests of {@code path}. */
    int hits(String path) {
        AtomicInteger n = byPath.get(path);
        return n == null ? 0 : n.get();
    }

    /** Requests that were neither GET nor HEAD. */
    int writes() {
        return writes.get();
    }

    /** {@code robots.txt}'s text; {@code null} answers 404. */
    void robots(String text) {
        robots = text;
    }

    void redirect(String location) {
        redirect = location;
    }

    /** What {@code %KEY%} becomes in a page. */
    void names(Map<String, String> replacements) {
        names = Map.copyOf(replacements);
    }

    private byte[] named(byte[] page) {
        String text = new String(page, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> name : names.entrySet()) {
            text = text.replace("%" + name.getKey() + "%", name.getValue());
        }
        return text.getBytes(StandardCharsets.UTF_8);
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
