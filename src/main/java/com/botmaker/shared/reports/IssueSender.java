package com.botmaker.shared.reports;

import com.botmaker.runlog.IssueText;
import com.botmaker.runlog.Problem;
import com.botmaker.runlog.RunFacts;
import com.botmaker.runlog.RunLogs;
import com.botmaker.runlog.Scrubber;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sends a run's problems as issues of the bot's repository (run reports spec §3): a problem with an open issue —
 * found by its fingerprint's hidden marker — is a comment on it; one whose last issue is closed comes back as a new
 * issue naming the old when the bot's version changed, else as a comment on the closed one; any other opens an
 * issue. Texts are written scrubbed, before anything is sent: what is queued is what the runner was shown.
 *
 * <p>Nothing is lost when GitHub cannot be reached or refuses: that report and every one after it are kept in the
 * run's folder, {@value #PENDING}, and {@link #resend} sends them on a later run.
 */
public final class IssueSender {

    /** The file of a run's folder that keeps the reports not sent yet. */
    public static final String PENDING = "reports-pending.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IssueSender() {
    }

    /** What became of one report. */
    public enum Outcome {
        CREATED("Opened an issue"),
        COMMENTED("Commented on its issue"),
        QUEUED("Kept for the next run");

        private final String displayName;

        Outcome(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * One report as it is sent: scrubbed texts, ready to go.
     *
     * @param fingerprint its problem's fingerprint
     * @param title       the issue's title
     * @param body        the issue's body
     * @param comment     the comment when the issue exists
     * @param botVersion  the run's bot version, to tell a closed issue's problem coming back on another
     */
    public record Report(String fingerprint, String title, String body, String comment, String botVersion) {

        /** {@code p}'s report for a run of {@code facts}, scrubbed by {@code scrubber}. */
        public static Report of(Problem p, RunFacts facts, Scrubber scrubber) {
            return new Report(p.fingerprint(), IssueText.title(p, scrubber),
                    IssueText.body(p, facts, scrubber), IssueText.comment(p, facts, scrubber),
                    scrubber.scrub(facts).tag());
        }
    }

    /**
     * What became of a report.
     *
     * @param report  the report
     * @param outcome what was done
     * @param issue   the issue opened or commented on; -1 when queued
     * @param error   why it was queued, or empty
     */
    public record Sent(Report report, Outcome outcome, int issue, String error) {
    }

    /** Sends {@code reports} through {@code api}; those that could not go are kept in {@code runFolder}. */
    public static List<Sent> send(List<Report> reports, IssueApi api, Path runFolder) {
        List<Sent> sent = new ArrayList<>();
        List<Report> left = new ArrayList<>();
        String error = null;
        for (Report r : reports) {
            if (error == null) {
                try {
                    sent.add(deliver(r, api));
                    continue;
                } catch (IOException | RuntimeException e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
            left.add(r);
            sent.add(new Sent(r, Outcome.QUEUED, -1, error));
        }
        keep(runFolder, left);
        return sent;
    }

    /** Sends what {@code runFolder} kept from an earlier try; what fails again stays. */
    public static List<Sent> resend(Path runFolder, IssueApi api) {
        List<Report> pending = pending(runFolder);
        return pending.isEmpty() ? List.of() : send(pending, api, runFolder);
    }

    /** The reports {@code runFolder} keeps, none when it keeps none or the file does not read. */
    public static List<Report> pending(Path runFolder) {
        Path file = runFolder.resolve(PENDING);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return MAPPER.readValue(file.toFile(), new TypeReference<List<Report>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Sent deliver(Report r, IssueApi api) throws IOException {
        List<IssueApi.Issue> found = api.find(IssueText.marker(r.fingerprint()));
        Optional<IssueApi.Issue> open = found.stream().filter(IssueApi.Issue::open).findFirst();
        if (open.isPresent()) {
            api.comment(open.get().number(), r.comment());
            return new Sent(r, Outcome.COMMENTED, open.get().number(), "");
        }
        if (!found.isEmpty()) {
            IssueApi.Issue closed = found.getFirst();
            if (r.botVersion().equals(versionOf(closed))) {
                api.comment(closed.number(), r.comment());
                return new Sent(r, Outcome.COMMENTED, closed.number(), "");
            }
            int number = api.create(r.title(), "Seen before in #" + closed.number() + ", closed, on "
                    + versionOf(closed) + ".\n\n" + r.body());
            return new Sent(r, Outcome.CREATED, number, "");
        }
        return new Sent(r, Outcome.CREATED, api.create(r.title(), r.body()), "");
    }

    /** The bot version an issue's report names, {@value RunFacts#UNKNOWN} when it names none. */
    private static String versionOf(IssueApi.Issue issue) {
        return RunLogs.readIssue(issue.body(), null, List.of()) instanceof RunLogs.ReadableIssue read
                && !read.report().occurrences().isEmpty()
                ? read.report().occurrences().getFirst().value("botVersion") : RunFacts.UNKNOWN;
    }

    private static void keep(Path runFolder, List<Report> left) {
        Path file = runFolder.resolve(PENDING);
        try {
            if (left.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(runFolder);
                // Written aside, then moved: a crash mid-write must not lose what was kept.
                Path part = runFolder.resolve(PENDING + ".part");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(part.toFile(), left);
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            System.err.println("[BotMaker] reports not kept for the next run: " + e.getMessage());
        }
    }
}
