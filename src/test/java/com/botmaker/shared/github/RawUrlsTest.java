package com.botmaker.shared.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The gallery and the registry move to {@link GitHubConfig#NEXT_OWNER} in two steps (2026-09-18): a Studio
 * that reads the new owner first and the old one second ships before either repository moves. These hold the
 * order, and that a reader gets the second URL's body when the first answers 404 — the state every Studio is
 * in until the move.
 */
class RawUrlsTest {

    @Test
    void theNewOwnerIsTriedFirstAndTheOldOneStillAnswers() {
        assertEquals(List.of(
                "https://raw.githubusercontent.com/BotMakerDev/botmaker-gallery/main/catalog.json",
                "https://raw.githubusercontent.com/LiQiyeDev/botmaker-gallery/main/catalog.json"),
                GitHubConfig.catalogRawUrls());
        assertEquals(List.of(
                "https://raw.githubusercontent.com/BotMakerDev/botmaker-plugin-registry/main/index.json",
                "https://raw.githubusercontent.com/LiQiyeDev/botmaker-plugin-registry/main/index.json"),
                GitHubConfig.registryIndexRawUrls());
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
