package com.botmaker.shared.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A refused read becomes one sentence with GitHub's own words, and a spent rate limit says so. */
class GitHubErrorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://api.github.com/repos/o/r/contents/plugins?ref=main";

    @Test
    void aRefusalCarriesTheStatusAndGitHubsSentence() {
        GitHubError error = GitHubError.of(URL, 404, "{\"message\":\"Not Found\"}", OptionalLong.of(55), MAPPER);

        assertEquals(404, error.status());
        assertFalse(error.rateLimited());
        assertEquals("GitHub refused /repos/o/r/contents/plugins?ref=main — HTTP 404: Not Found", error.getMessage());
    }

    @Test
    void aSpentRateLimitIsNamedAndSaysHowToLiftIt() {
        GitHubError error = GitHubError.of(URL, 403, "{\"message\":\"API rate limit exceeded for 1.2.3.4.\"}",
                OptionalLong.of(0), MAPPER);

        assertTrue(error.rateLimited());
        assertTrue(error.getMessage().startsWith("GitHub's rate limit is used up"), error.getMessage());
        assertTrue(error.getMessage().contains("API rate limit exceeded"), error.getMessage());
    }

    @Test
    void aForbiddenWithBudgetLeftIsNotARateLimit() {
        GitHubError error = GitHubError.of(URL, 403, "<html>proxy</html>", OptionalLong.of(12), MAPPER);

        assertFalse(error.rateLimited());
        assertTrue(error.getMessage().endsWith("HTTP 403"), error.getMessage());
    }
}
