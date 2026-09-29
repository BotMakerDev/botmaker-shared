package com.botmaker.shared.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.OptionalLong;

/**
 * A read GitHub did not answer with 200, as {@link GitHubClient#getOrFail} fails its future with it.
 *
 * <p>{@link GitHubClient#get} answers {@code null} for every failure, which suits a caller for whom a missing
 * value is a missing value. A caller that lists something cannot tell "nothing there" from "GitHub said no"
 * that way: a used-up rate limit read as "0 plugins" in the dashboard's Catalog tab until 2026-09-29. This
 * carries the status and GitHub's own sentence, and says so plainly when the rate limit is the reason.
 *
 * @param status             the HTTP status, or {@link #UNREACHABLE} when no response came back
 * @param rateLimitRemaining GitHub's {@code X-RateLimit-Remaining}, when the response carried it
 */
public final class GitHubError extends RuntimeException {

    /** The status reported when the request never reached GitHub (offline, DNS, a timeout). */
    public static final int UNREACHABLE = -1;

    private final int status;
    private final OptionalLong rateLimitRemaining;

    public GitHubError(int status, String message, OptionalLong rateLimitRemaining) {
        super(message);
        this.status = status;
        this.rateLimitRemaining = rateLimitRemaining;
    }

    public int status() {
        return status;
    }

    public OptionalLong rateLimitRemaining() {
        return rateLimitRemaining;
    }

    /** Whether the request was refused because the hourly budget is spent. */
    public boolean rateLimited() {
        return (status == 403 || status == 429) && rateLimitRemaining.orElse(-1) == 0;
    }

    /**
     * Reads a non-200 response into one sentence.
     *
     * <p>GitHub puts its reason in the body's {@code message}; that is used as written. A rate limit gets a
     * sentence of its own in front, because GitHub's ("API rate limit exceeded for 1.2.3.4") says neither that
     * signing in lifts it nor that it resets within the hour.
     */
    static GitHubError of(String url, int status, String body, OptionalLong rateLimitRemaining, ObjectMapper mapper) {
        String said = "";
        try {
            JsonNode json = mapper.readTree(body == null ? "" : body);
            said = json == null ? "" : json.path("message").asText("");
        } catch (Exception ignored) {
            // Not JSON (a proxy's HTML page): the status alone is what is known.
        }
        String reason = said.isBlank() ? "HTTP " + status : "HTTP " + status + ": " + said;
        GitHubError error = new GitHubError(status, "GitHub refused " + path(url) + " — " + reason,
                rateLimitRemaining);
        return error.rateLimited()
                ? new GitHubError(status, "GitHub's rate limit is used up (60 requests an hour signed out, 5,000 "
                        + "signed in) — sign in, or wait for it to reset. " + error.getMessage(), rateLimitRemaining)
                : error;
    }

    /** The URL without its host, which is always the API's. */
    private static String path(String url) {
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        return slash < 0 ? url : url.substring(slash);
    }
}
