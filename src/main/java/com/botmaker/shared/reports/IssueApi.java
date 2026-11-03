package com.botmaker.shared.reports;

import com.botmaker.runlog.ReportTarget;
import com.botmaker.shared.github.GitHubClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The issues of one repository, as {@link IssueSender} needs them. {@link #github} is GitHub's; a test gives its own.
 * Every call throws {@link IOException} when the repository cannot be reached or refuses.
 */
public interface IssueApi {

    /**
     * An issue found.
     *
     * @param number its number
     * @param open   whether it is open
     * @param body   its text
     */
    record Issue(int number, boolean open, String body) {
    }

    /** The issues, open or closed, whose body holds {@code marker}, newest first. */
    List<Issue> find(String marker) throws IOException;

    /** Opens an issue; its number. */
    int create(String title, String body) throws IOException;

    /** Comments on issue {@code number}. */
    void comment(int number, String body) throws IOException;

    /** {@code target}'s issues through {@code client}, as the user {@code token} belongs to. */
    static IssueApi github(GitHubClient client, ReportTarget target, String token) {
        return new IssueApi() {
            @Override
            public List<Issue> find(String marker) throws IOException {
                List<Issue> found = new ArrayList<>();
                for (var item : join(client.searchIssues(target.owner(), target.repo(), marker, token))
                        .path("items")) {
                    String body = item.path("body").asText("");
                    // Its last line: the log above it is the runner's text and may quote another marker.
                    if (body.stripTrailing().endsWith(marker) && !item.has("pull_request")) {
                        found.add(new Issue(item.path("number").asInt(), "open".equals(item.path("state").asText()),
                                body));
                    }
                }
                found.sort((a, b) -> Integer.compare(b.number(), a.number()));
                return found;
            }

            @Override
            public int create(String title, String body) throws IOException {
                int number = join(client.createIssue(target.owner(), target.repo(), title, body, token))
                        .path("number").asInt(-1);
                if (number < 0) throw new IOException("GitHub opened no issue");
                return number;
            }

            @Override
            public void comment(int number, String body) throws IOException {
                join(client.commentIssue(target.owner(), target.repo(), number, body, token));
            }
        };
    }

    private static <T> T join(CompletableFuture<T> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException(cause.getMessage(), cause);
        }
    }
}
