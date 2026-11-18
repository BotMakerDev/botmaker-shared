// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.web;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>{@code robots.txt}, honoured</b> (spec §3, polite): fetched once per origin, parsed by crawler-commons'
 * {@link SimpleRobotRulesParser} for the agent {@value #AGENT} (wildcards, {@code Allow}/{@code Disallow} precedence,
 * agent groups, {@code Crawl-delay}). A missing file (4xx) allows everything; a server error or no answer allows
 * nothing, as RFC 9309 says.
 */
public final class Robots {

    /** The product token a {@code robots.txt} group names. */
    public static final String AGENT = "BotMaker";

    /** What fetching one {@code robots.txt} gave: its status ({@code 0} for no answer), body and type. */
    public record Fetched(int status, byte[] body, String contentType) {

        public Fetched {
            body = body == null ? new byte[0] : body.clone();
            contentType = contentType == null ? "text/plain" : contentType;
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /** Fetches one {@code robots.txt}; never throws. */
    @FunctionalInterface
    public interface Fetcher {
        Fetched fetch(URI robotsTxt);
    }

    private final String userAgent;
    private final Fetcher fetcher;
    private final Map<String, BaseRobotRules> rules = new ConcurrentHashMap<>();

    public Robots(String userAgent, Fetcher fetcher) {
        this.userAgent = userAgent;
        this.fetcher = fetcher;
    }

    /** Rules fetched over HTTP with {@link #userAgent(String)} of {@code version}. */
    public static Robots over(String version) {
        String agent = userAgent(version);
        return new Robots(agent, http(agent));
    }

    /** {@code BotMaker/<version> (+discovery, read-only)}. */
    public static String userAgent(String version) {
        return AGENT + "/" + (version == null || version.isBlank() ? "dev" : version.strip())
                + " (+discovery, read-only)";
    }

    /** The JDK's client asking as {@code userAgent}, ten seconds at most. */
    public static Fetcher http(String userAgent) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return robotsTxt -> {
            try {
                HttpResponse<byte[]> answer = client.send(HttpRequest.newBuilder(robotsTxt)
                        .timeout(Duration.ofSeconds(10)).header("User-Agent", userAgent).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                return new Fetched(answer.statusCode(), answer.body(),
                        answer.headers().firstValue("Content-Type").orElse("text/plain"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Fetched(0, null, null);
            } catch (IOException | IllegalArgumentException e) {
                return new Fetched(0, null, null);
            }
        };
    }

    /** The user agent this asks as, which the browser's requests carry too. */
    public String userAgent() {
        return userAgent;
    }

    /** Whether {@code url} may be loaded. */
    public boolean allows(URI url) {
        return rules(url).isAllowed(url.toString());
    }

    /** Whether nothing of {@code url}'s origin may be loaded: {@code Disallow: /}, or no answer. */
    public boolean allowsNothing(URI url) {
        BaseRobotRules rules = rules(url);
        return rules.isAllowNone() || !rules.isAllowed(origin(url) + "/");
    }

    /** The origin's {@code Crawl-delay}, if it gives one. */
    public Optional<Duration> crawlDelay(URI url) {
        long delay = rules(url).getCrawlDelay();
        return delay == BaseRobotRules.UNSET_CRAWL_DELAY || delay <= 0 ? Optional.empty()
                : Optional.of(Duration.ofMillis(delay));
    }

    private BaseRobotRules rules(URI url) {
        return rules.computeIfAbsent(origin(url), origin -> {
            URI robotsTxt = URI.create(origin + "/robots.txt");
            Fetched fetched = fetcher.fetch(robotsTxt);
            SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
            if (fetched.status() >= 200 && fetched.status() < 300) {
                return parser.parseContent(robotsTxt.toString(), fetched.body(), fetched.contentType(),
                        List.of(AGENT.toLowerCase(Locale.ROOT)));
            }
            // A redirect the client would not follow (https to http) is no file, as RFC 9309 allows; 5xx or no
            // answer allows nothing.
            int status = fetched.status();
            return parser.failedFetch(status >= 300 && status < 500 ? (status < 400 ? 404 : status) : 503);
        });
    }

    private static String origin(URI url) {
        String scheme = url.getScheme() == null ? "http" : url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        return scheme + "://" + host + (url.getPort() < 0 ? "" : ":" + url.getPort());
    }
}
