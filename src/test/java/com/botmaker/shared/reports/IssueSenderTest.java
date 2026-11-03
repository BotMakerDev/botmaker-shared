package com.botmaker.shared.reports;

import com.botmaker.runlog.IssueText;
import com.botmaker.runlog.Problem;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.RunFacts;
import com.botmaker.runlog.Scrubber;
import com.botmaker.runlog.api.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reports to issues: a comment on the open one, a new one past a closed one, queued when GitHub fails. */
class IssueSenderTest {

    @TempDir
    Path run;

    private static final Scrubber SCRUB = Scrubber.of("/home/ben", "ben", List.of());

    private static RunFacts facts(String tag) {
        return new RunFacts("com.example:bot", tag, "abc", false, Map.of(), "Linux", 0, Map.of());
    }

    private static Problem problem(int line) {
        return new Problem("com.example.Collect:" + line + "|x.Screens.goTo|warn", Level.WARN, "com.example.Collect",
                line, "x.Screens", "goTo", "Home", "stuck", 2, 0, 0, List.of(), 0);
    }

    /** Issues kept in a list; {@code down} makes every call fail as an unreachable GitHub would. */
    private static final class FakeIssues implements IssueApi {
        final List<Issue> issues = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        boolean down;

        @Override
        public List<Issue> find(String marker) throws IOException {
            if (down) throw new IOException("GitHub could not be reached");
            return issues.stream().filter(i -> i.body().contains(marker))
                    .sorted((a, b) -> Integer.compare(b.number(), a.number())).toList();
        }

        @Override
        public int create(String title, String body) throws IOException {
            if (down) throw new IOException("403");
            issues.add(new Issue(issues.size() + 1, true, body));
            return issues.size();
        }

        @Override
        public void comment(int number, String body) throws IOException {
            if (down) throw new IOException("403");
            comments.add("#" + number + " " + body);
        }
    }

    private static IssueSender.Report report(int line, String tag) {
        return IssueSender.Report.of(problem(line), facts(tag), SCRUB);
    }

    @Test
    void aNewProblemOpensAnIssueAndComesBackAsAComment() {
        FakeIssues api = new FakeIssues();
        List<IssueSender.Sent> first = IssueSender.send(List.of(report(57, "v1")), api, run);
        assertEquals(IssueSender.Outcome.CREATED, first.getFirst().outcome());
        assertEquals("Warning: Screens.goTo at Collect:57", first.getFirst().report().title());
        List<IssueSender.Sent> again = IssueSender.send(List.of(report(57, "v1")), api, run);
        assertEquals(IssueSender.Outcome.COMMENTED, again.getFirst().outcome());
        assertEquals(1, api.issues.size());
        assertTrue(api.comments.getFirst().startsWith("#1 ×2 on v1"), api.comments.getFirst());
    }

    @Test
    void aClosedIssuesProblemOnANewerVersionOpensANewOneNamingIt() {
        FakeIssues api = new FakeIssues();
        api.issues.add(new IssueApi.Issue(7, false, IssueText.body(problem(57), facts("v1"), SCRUB)));
        IssueSender.Sent sameVersion = IssueSender.send(List.of(report(57, "v1")), api, run).getFirst();
        assertEquals(IssueSender.Outcome.COMMENTED, sameVersion.outcome());
        assertEquals(7, sameVersion.issue());
        IssueSender.Sent newer = IssueSender.send(List.of(report(57, "v2")), api, run).getFirst();
        assertEquals(IssueSender.Outcome.CREATED, newer.outcome());
        assertTrue(api.issues.getLast().body().startsWith("Seen before in #7, closed, on v1."));
    }

    @Test
    void failedSendIsQueued() {
        FakeIssues api = new FakeIssues();
        api.down = true;
        List<IssueSender.Sent> sent = IssueSender.send(List.of(report(57, "v1"), report(58, "v1")), api, run);
        assertTrue(sent.stream().allMatch(s -> s.outcome() == IssueSender.Outcome.QUEUED));
        assertEquals("GitHub could not be reached", sent.getFirst().error());
        assertEquals(2, IssueSender.pending(run).size());
        api.down = false;
        List<IssueSender.Sent> later = IssueSender.resend(run, api);
        assertEquals(2, later.size());
        assertTrue(later.stream().allMatch(s -> s.outcome() == IssueSender.Outcome.CREATED));
        assertFalse(Files.exists(run.resolve(IssueSender.PENDING)), "nothing left to send");
    }

    @Test
    void noRemoteMeansNoTarget() {
        assertEquals(Optional.empty(), ReportTarget.ofConfig("[core]\n\tbare = false\n"));
        assertEquals(Optional.empty(), ReportTarget.of(run));
        assertEquals(Optional.of(new ReportTarget("author", "bot")), ReportTarget.ofConfig("""
                [remote "origin"]
                \turl = git@github.com:me/bot.git
                [remote "upstream"]
                \turl = https://github.com/author/bot.git
                """), "upstream before origin");
        assertEquals(Optional.of(new ReportTarget("me", "my-bot")),
                ReportTarget.ofConfig("[remote \"origin\"]\n\turl = https://github.com/me/my-bot\n"));
        assertEquals(Optional.empty(), ReportTarget.ofConfig("[remote \"origin\"]\n\turl = https://gitlab.com/me/b\n"));
    }

    @Test
    void anExportIsScrubbedText(@TempDir Path out) throws IOException {
        Path folder = Files.createDirectories(run.resolve("20261103-101500"));
        Files.writeString(folder.resolve("run.jsonl"), "{\"text\":\"/home/ben/x\"}\n");
        Files.write(folder.resolve("miss.png"), new byte[]{1, 2});
        Path zip = out.resolve("logs.zip");
        assertEquals(List.of("20261103-101500/run.jsonl"), ExportLogs.zip(folder, zip, SCRUB));
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertEquals("{\"text\":\"~/x\"}\n", new String(z.getInputStream(z.getEntry("20261103-101500/run.jsonl"))
                    .readAllBytes()));
        }
    }
}
