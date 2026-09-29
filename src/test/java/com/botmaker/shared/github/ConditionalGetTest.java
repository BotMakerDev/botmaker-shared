package com.botmaker.shared.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A repeat GET asks with the ETag it was given, and a 304 answers with the body it stands for. */
class ConditionalGetTest {

    private HttpServer server;
    private final List<String> asked = new ArrayList<>();
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r", exchange -> {
            String match = exchange.getRequestHeaders().getFirst("If-None-Match");
            asked.add(match == null ? "" : match);
            if ("\"v1\"".equals(match)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                byte[] body = "{\"name\":\"r\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repos/o/r";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aRepeatIsAskedConditionallyAndA304ReadsAsTheCachedBody() {
        GitHubClient client = new GitHubClient();

        JsonNode first = client.get(url, "t").join();
        JsonNode second = client.getOrFail(url, "t").join();

        assertEquals("r", first.path("name").asText());
        assertEquals("r", second.path("name").asText());
        assertEquals(List.of("", "\"v1\""), asked);
    }

    @Test
    void anotherTokenDoesNotShareTheCache() {
        GitHubClient client = new GitHubClient();

        client.get(url, "one").join();
        client.get(url, "two").join();

        assertEquals(List.of("", ""), asked, "a body read with one token is never served to another");
    }

    @Test
    void a304WithNothingCachedIsNotABody() {
        server.removeContext("/repos/o/r");
        server.createContext("/repos/o/r", exchange -> {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        });

        assertNull(new GitHubClient().get(url, null).join());
    }
}
