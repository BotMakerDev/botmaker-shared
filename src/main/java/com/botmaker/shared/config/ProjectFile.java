package com.botmaker.shared.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * {@code botmaker-project.properties} read from a <b>directory</b>, for everything that holds a project rather
 * than being one.
 *
 * <p>{@link ProjectProperties} reads the same file off the <em>classpath</em>, because a running bot's copy is
 * a resource inside its own jar. An editor, a launcher and a plugin serving a project all hold a resources
 * directory instead, and each had grown its own load-modify-parse of the same file — four copies free to
 * disagree about what a missing file, a blank value or an unparseable number means. The keys, the parsing and
 * those answers belong to one place; this is the directory-shaped half of it.
 *
 * <p><b>It writes one key at a time since 2026-09-22</b>, and the rule it replaces is worth recording: this
 * class was reads-only because writing the file stamped a schema version from the SDK's own migration
 * ledger, so the write path had to stay with whoever owned that ledger. <b>The ledger is gone</b> — it had
 * exactly one entry point, {@code capture.json}, and that file is deleted now that a project's capture
 * source is the expression {@code Sdk.captureSource()} returns. Nothing left in this file is versioned, so
 * there is no stamp for a second writer to drop.
 *
 * <p>{@link #set} is a load-modify-store of the whole file, so a key nobody here knows about survives a
 * write by somebody who does. It is not a general settings API: the keys are
 * {@link ProjectProperties}', and what may be written is what no {@code @Managed} value already says.
 *
 * <p>Every answer here is best-effort: an absent directory, an absent file, an unreadable file and an
 * unparseable value all yield the caller's own default rather than an exception. A project file that has been
 * hand-edited must not stop a launch.
 */
public final class ProjectFile {

    private ProjectFile() {
    }

    /** The whole file, or an empty set when it is absent or unreadable — the two are the same answer here. */
    public static Properties read(Path resourcesDir) {
        Properties properties = new Properties();
        if (resourcesDir == null) return properties;
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        if (!Files.exists(file)) return properties;
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException unreadable) {
            return new Properties();
        }
        return properties;
    }

    /**
     * Writes one key, leaving every other line of the file as it was.
     *
     * <p>Best-effort like every read here: an absent directory or an unwritable file is a no-op rather than
     * an exception, because what this records is secondary — the caller's own edit has already happened, and
     * failing the edit because a properties file could not be written would lose the thing the user actually
     * did.
     *
     * @param value the new value, or {@code null} to remove the key
     * @return whether the file now says what was asked
     */
    public static boolean set(Path resourcesDir, String key, String value) {
        if (resourcesDir == null || key == null || key.isBlank()) return false;
        Path file = resourcesDir.resolve(ProjectProperties.FILE_NAME);
        Properties properties = read(resourcesDir);
        if (value == null || value.isBlank()) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value.trim());
        }
        try (var out = Files.newOutputStream(file)) {
            properties.store(out, null);
            return true;
        } catch (IOException unwritable) {
            return false;
        }
    }

    /** One key's trimmed value, or {@code null} when the key, the file or the directory is absent. */
    public static String value(Path resourcesDir, String key) {
        String value = read(resourcesDir).getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The {@code capture.source} spec — {@code desktop} | {@code monitor:<i>} | … — or {@code null}. */
    public static String captureSource(Path resourcesDir) {
        return value(resourcesDir, ProjectProperties.KEY_CAPTURE_SOURCE);
    }

    /** The raw {@code launch.target} spec, or {@code null} when the project configures none. */
    public static String launchTarget(Path resourcesDir) {
        return value(resourcesDir, ProjectProperties.KEY_LAUNCH_TARGET);
    }

    // captureSize stood here until 2026-09-22, reading capture.width / capture.height. Nothing ever wrote
    // either key, so it answered null on every call and every caller took its own default — see the
    // tombstone in ProjectProperties for why both halves of that size were deleted rather than one of them
    // being given a writer. QuickLaunch now starts a nested session at BackgroundLauncher's own default,
    // which is what it already did for every project in existence.

    /**
     * Whether the project runs its bot on a private nested display: {@code true} unless the key is explicitly
     * off, which is {@link ProjectProperties#sessionIsolated()}'s rule and the SDK's default-on isolation.
     */
    public static boolean sessionIsolated(Path resourcesDir) {
        String spec = value(resourcesDir, ProjectProperties.KEY_SESSION_ISOLATED);
        if (spec == null) return true;
        return switch (spec.toLowerCase()) {
            case "false", "0", "no", "off" -> false;
            default -> true;
        };
    }

}
