package com.botmaker.shared.config;

import java.awt.Dimension;
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
 * <p><b>Reads only, and deliberately.</b> Writing this file stamps a schema version from the editor's own
 * migration ledger, so the write path stays with whoever owns that ledger. A reader can be shared; a writer
 * with two owners is how a stamp gets silently dropped.
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

    /**
     * The project's standard capture resolution — the size its image templates were authored at, and therefore
     * the size a private display is created at — or {@code null} when either key is absent or unparseable.
     */
    public static Dimension captureSize(Path resourcesDir) {
        Properties properties = read(resourcesDir);
        Integer width = number(properties.getProperty(ProjectProperties.KEY_CAPTURE_WIDTH));
        Integer height = number(properties.getProperty(ProjectProperties.KEY_CAPTURE_HEIGHT));
        if (width == null || height == null || width <= 0 || height <= 0) return null;
        return new Dimension(width, height);
    }

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

    private static Integer number(String value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
