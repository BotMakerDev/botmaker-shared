package com.botmaker.shared.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * The OAuth 2.0 <b>device flow</b> (RFC 8628), one provider's endpoints and client: request a code, then poll
 * until the user authorizes it in their browser. No library implements it, so it is hand-rolled over the JDK
 * {@link HttpClient}; GitHub's and Google's sign-ins both run it and differ only in what this record holds.
 *
 * <p>It stores nothing: the caller keeps the token it returns (see {@link Credentials}).
 *
 * @param provider       the provider's name, for the failure message ("GitHub sign-in failed: …")
 * @param deviceCodeUrl  where step 1 asks for a code
 * @param tokenUrl       where step 2 polls for the token
 * @param codeParams     the form step 1 sends ({@code client_id}, {@code scope})
 * @param clientParams   what every poll sends besides the device code ({@code client_id}, a secret if any)
 * @param defaultExpiry  the code's life in seconds when the response omits {@code expires_in}
 */
public record DeviceFlow(String provider, String deviceCodeUrl, String tokenUrl, Map<String, String> codeParams,
                         Map<String, String> clientParams, int defaultExpiry) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One client for every flow, built on its first request: building one costs a few hundred ms, and Studio
     * constructs its sign-ins on the FX thread while its first window shows (2026-10-30).
     */
    private static final class Http {
        static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public DeviceFlow {
        codeParams = Map.copyOf(codeParams);
        clientParams = Map.copyOf(clientParams);
    }

    /** Step 1: request a device + user code. */
    public CompletableFuture<DeviceCode> requestCode() {
        return Http.CLIENT.sendAsync(post(deviceCodeUrl, codeParams), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    try {
                        JsonNode n = MAPPER.readTree(resp.body());
                        if (n.hasNonNull("device_code")) {
                            // GitHub names it verification_uri, Google verification_url.
                            String verify = n.path("verification_uri").asText(n.path("verification_url").asText(""));
                            return new DeviceCode(
                                    n.get("device_code").asText(),
                                    n.get("user_code").asText(),
                                    verify,
                                    n.path("interval").asInt(5),
                                    n.path("expires_in").asInt(defaultExpiry));
                        }
                        throw new RuntimeException("Device-code request failed: " + resp.body());
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException("Device-code request failed: " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Step 2: poll until the user authorizes (or the code expires / is denied) and return the access token.
     * Runs on a background thread.
     *
     * <p>Cancelling the returned future does not stop the loop, which runs inside {@code supplyAsync}: a
     * dialog closed with Cancel kept polling for the code's whole life and, had the user finished in the
     * browser after all, stored a token nobody was waiting for (the dashboard, 2026-09-29). The loop asks
     * {@code cancelled} before each poll and fails with a {@link CancellationException}.
     */
    public CompletableFuture<String> pollForToken(DeviceCode code, BooleanSupplier cancelled) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + code.expiresInSeconds() * 1000L;
            int interval = Math.max(1, code.intervalSeconds());
            while (System.currentTimeMillis() < deadline) {
                sleep(interval);
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Sign-in was cancelled.");
                }
                JsonNode n = pollOnce(code.deviceCode());
                if (n == null) continue;
                if (n.hasNonNull("access_token")) {
                    return n.get("access_token").asText();
                }
                String error = n.path("error").asText("");
                switch (error) {
                    case "authorization_pending" -> { /* keep waiting */ }
                    case "slow_down" -> interval += 5;
                    case "access_denied" -> throw new RuntimeException("Authorization was denied.");
                    case "expired_token" -> throw new RuntimeException("The code expired. Please try again.");
                    default -> {
                        if (!error.isBlank()) throw new RuntimeException(provider + " sign-in failed: " + error);
                    }
                }
            }
            throw new RuntimeException("Sign-in timed out. Please try again.");
        });
    }

    private JsonNode pollOnce(String deviceCode) {
        Map<String, String> params = new LinkedHashMap<>(clientParams);
        params.put("device_code", deviceCode);
        params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        try {
            HttpResponse<String> resp = Http.CLIENT.send(post(tokenUrl, params), HttpResponse.BodyHandlers.ofString());
            return MAPPER.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null; // transient; keep polling
        }
    }

    private static HttpRequest post(String url, Map<String, String> params) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
