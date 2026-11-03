package com.botmaker.shared.reports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a bot's reports go (run reports spec §3): the GitHub repository of its {@code upstream} remote — a run
 * copy's author — else of its {@code origin}, the author's own project; none without either, and then the runner
 * only exports logs.
 *
 * @param owner the repository's owner
 * @param repo  its name
 */
public record ReportTarget(String owner, String repo) {

    private static final Pattern REMOTE = Pattern.compile("^\\s*\\[remote \"([^\"]+)\"]\\s*$");
    private static final Pattern URL = Pattern.compile("^\\s*url\\s*=\\s*(\\S+)\\s*$");
    private static final Pattern GITHUB = Pattern.compile(
            "^(?:https?://(?:[^@/]+@)?github\\.com/|ssh://git@github\\.com/|git@github\\.com:)([^/]+)/([^/]+?)(?:\\.git)?/?$");

    /** The target of the bot whose folder is {@code botFolder}, read from its git configuration. */
    public static Optional<ReportTarget> of(Path botFolder) {
        Path git = botFolder.resolve(".git");
        try {
            if (Files.isRegularFile(git)) { // a worktree: "gitdir: <path>"
                String line = Files.readString(git).strip();
                if (!line.startsWith("gitdir:")) return Optional.empty();
                git = botFolder.resolve(line.substring("gitdir:".length()).strip());
                Path common = git.resolve("commondir");
                if (Files.isRegularFile(common)) git = git.resolve(Files.readString(common).strip());
            }
            Path config = git.resolve("config");
            return Files.isRegularFile(config) ? ofConfig(Files.readString(config)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The target a git {@code config} file's text names: {@code upstream}'s GitHub repository, else {@code origin}'s. */
    static Optional<ReportTarget> ofConfig(String config) {
        Map<String, String> urls = new LinkedHashMap<>();
        String remote = null;
        for (String line : config.split("\\R")) {
            Matcher r = REMOTE.matcher(line);
            if (r.matches()) {
                remote = r.group(1);
                continue;
            }
            if (line.strip().startsWith("[")) {
                remote = null;
                continue;
            }
            Matcher u = URL.matcher(line);
            if (remote != null && u.matches()) urls.putIfAbsent(remote, u.group(1));
        }
        for (String name : new String[]{"upstream", "origin"}) {
            Matcher m = GITHUB.matcher(urls.getOrDefault(name, ""));
            if (m.matches()) return Optional.of(new ReportTarget(m.group(1), m.group(2)));
        }
        return Optional.empty();
    }
}
