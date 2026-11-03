package com.botmaker.shared.runlog;

import java.util.Map;
import java.util.TreeMap;

/**
 * What a run was, written as the first line of its {@code run.jsonl} ({@link RunLog}): which bot at which version,
 * on which OS, when. A report names the run by these (spec
 * {@code docs/superpowers/specs/2026-11-03-run-reports-design.md} §2).
 *
 * <p><b>Only what any host knows.</b> What a plugin knows of its run — its flow, the game, the platform, the
 * runner's choices — is in {@code details}, labels the plugin names and fills; this module and Studio show and
 * group by them without knowing what any one means, so no plugin's vocabulary enters either.
 *
 * <p>Every text is {@value #UNKNOWN} when the run could not tell, never null or empty, so a reader filters by a
 * field without a missing case. {@code versions} maps a plugin's or the SDK's coordinate to its version.
 *
 * @param bot         the bot's {@code groupId:artifactId}
 * @param tag         the bot's release tag, or {@value #UNKNOWN} for a working copy
 * @param commit      the commit the bot was built from
 * @param dirty       whether the copy differs from that commit
 * @param os          the operating system and its version
 * @param startMillis when the run started
 * @param details     a plugin's own facts of the run, label to value, in the plugin's words
 */
public record RunFacts(String bot, String tag, String commit, boolean dirty, Map<String, String> versions,
                       String os, long startMillis, Map<String, String> details) {

    /** What a fact the run could not tell reads as. */
    public static final String UNKNOWN = "unknown";

    public RunFacts {
        bot = known(bot);
        tag = known(tag);
        commit = known(commit);
        versions = known(versions);
        os = known(os);
        details = known(details);
    }

    /** {@code map} without a null or blank key, each null or blank value {@value #UNKNOWN}. */
    private static Map<String, String> known(Map<String, String> map) {
        if (map == null) return Map.of();
        Map<String, String> kept = new TreeMap<>();
        map.forEach((k, v) -> {
            if (k != null && !k.isBlank()) kept.put(k.strip(), known(v));
        });
        return Map.copyOf(kept);
    }

    /** Facts that know only when the run started and on which OS: a run started outside a host. */
    public static RunFacts unknown(long startMillis) {
        return new RunFacts(null, null, null, false, null,
                System.getProperty("os.name", UNKNOWN) + " " + System.getProperty("os.version", ""), startMillis,
                null);
    }

    private static String known(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.strip();
    }
}
