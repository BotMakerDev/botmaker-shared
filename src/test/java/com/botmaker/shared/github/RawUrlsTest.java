package com.botmaker.shared.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The catalog's one address (2026-10-23), compiled into every Studio, and that a reader given several URLs gets
 * the second one's body when the first answers 404.
 */
class RawUrlsTest {

    @Test
    void theCatalogHasOneAddress() {
        assertEquals(List.of("https://raw.githubusercontent.com/BotMakerDev/botmaker-catalog/main/catalog.json"),
                GitHubConfig.catalogRawUrls());
    }

    @Test
    void aMissingFirstCopyFallsThroughToTheSecond() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean found = exchange.getRequestURI().getPath().startsWith("/old/");
            byte[] body = (found ? "{\"bots\":[]}" : "Not Found").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(found ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GitHubClient client = new GitHubClient();

            assertEquals("{\"bots\":[]}",
                    client.getFirstString(List.of(base + "/new/catalog.json", base + "/old/catalog.json")).join());
            assertNull(client.getFirstString(List.of(base + "/new/a", base + "/new/b")).join(),
                    "no copy anywhere is null, which every reader already treats as an empty catalog");
        } finally {
            server.stop(0);
        }
    }
}
