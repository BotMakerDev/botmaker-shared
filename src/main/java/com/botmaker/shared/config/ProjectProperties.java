package com.botmaker.shared.config;

import java.awt.Dimension;
import java.io.InputStream;
import java.util.Properties;

/**
 * The {@code botmaker-project.properties} a generated bot carries as a classpath resource — the file Studio
 * writes at project creation and the bot reads back at runtime.
 *
 * <p>It lives here, rather than in the SDK, because <b>both</b> consumers speak this file: Studio writes the
 * keys ({@code ProjectCreator}) and the SDK reads them. Two hand-kept copies of a key set do not stay
 * identical — {@link #KEY_CAPTURE_SOURCE} and friends are the single source of truth for both sides.
 *
 * <p>This reader is deliberately <b>raw</b>: it returns strings, ints and a {@link Dimension}, never an SDK
 * value type. The SDK's {@code internal.config.ProjectDefaults} maps those onto {@code CaptureSource} /
 * {@code Size}, which is where types shared cannot see belong.
 *
 * <p>Everything is best-effort: a missing file, missing key or unparseable value yields {@code null} so each
 * caller falls back to its own default. Loaded once and cached.
 *
 * <p>Recognised keys:
 * <ul>
 *   <li>{@link #KEY_CAPTURE_SOURCE} — {@code desktop} | {@code monitor:<index>} |
 *       {@code window:<titleSubstring>} | {@code emulator:<instanceName>}</li>
 *   <li>{@link #KEY_CAPTURE_WIDTH} / {@link #KEY_CAPTURE_HEIGHT} — the resolution templates were authored at</li>
 *   <li>{@link #KEY_LAUNCH_TARGET} — what the bot launches, in the {@code com.botmaker.shared.launch.LaunchSpec}
 *       grammar ({@code steam:<appId>}, {@code emu-app:<pkg>@<instance>}, …); read raw here</li>
 *   <li>{@link #KEY_DEBUG} — {@code true}/{@code false} (default on): the initial state of the bot's debug
 *       output switch</li>
 *   <li>{@link #KEY_SESSION_ISOLATED} — {@code true}/{@code false} (<b>default true</b>): whether the bot runs
 *       on a private nested display instead of the shared {@code :0}</li>
 *   <li>{@link #KEY_SESSION_BACKEND} — {@code gamescope} | {@code xephyr}: an explicit backend override for the
 *       nested display; normally unset, letting the launch kind pick (see {@code session.SessionBackends})</li>
 * </ul>
 */
public final class ProjectProperties {

    /** The file's name on disk — what Studio resolves against a project's resources dir when writing it. */
    public static final String FILE_NAME = "botmaker-project.properties";

    /** Classpath location of the same file inside a built bot, as the SDK reads it back. */
    public static final String RESOURCE = "/" + FILE_NAME;

    public static final String KEY_CAPTURE_SOURCE = "capture.source";
    public static final String KEY_CAPTURE_WIDTH = "capture.width";
    public static final String KEY_CAPTURE_HEIGHT = "capture.height";
    public static final String KEY_LAUNCH_TARGET = "launch.target";
    public static final String KEY_DEBUG = "debug";
    public static final String KEY_SESSION_ISOLATED = "session.isolated";
    public static final String KEY_SESSION_BACKEND = "session.backend";

    private static volatile Properties cached;
    private static volatile boolean loaded;

    private ProjectProperties() {}

    /**
     * Test seam: inject the parsed properties directly, bypassing the one-time classpath load — mirrors
     * {@code NativeControllerFactory.setForTesting}. Pass {@code null} to reset back to the classpath source.
     */
    static void setForTesting(Properties p) {
        synchronized (ProjectProperties.class) {
            cached = p;
            loaded = p != null;
        }
    }

    private static Properties props() {
        if (!loaded) {
            synchronized (ProjectProperties.class) {
                if (!loaded) {
                    Properties p = new Properties();
                    try (InputStream in = ProjectProperties.class.getResourceAsStream(RESOURCE)) {
                        if (in != null) {
                            p.load(in);
                        }
                    } catch (Exception ignored) {
                        // best-effort: absent/unreadable config leaves p empty
                    }
                    cached = p;
                    loaded = true;
                }
            }
        }
        return cached;
    }

    /** The trimmed value of {@code key}, or {@code null} when absent or blank. */
    public static String get(String key) {
        String value = props().getProperty(key);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** The raw {@code capture.source} spec, or {@code null} when unset. Parsed by the consumer. */
    public static String captureSource() {
        return get(KEY_CAPTURE_SOURCE);
    }

    /** The raw {@code launch.target} spec, or {@code null} when unset. Parse with {@code LaunchSpec.parse}. */
    public static String launchTarget() {
        return get(KEY_LAUNCH_TARGET);
    }

    /**
     * The configured debug-output default: {@code TRUE}/{@code FALSE} for an explicit {@code debug} key
     * ({@code true}/{@code 1}/{@code yes}/{@code on} → on; {@code false}/{@code 0}/{@code no}/{@code off} →
     * off), or {@code null} when the key is absent/unparseable so the caller keeps its own default.
     */
    public static Boolean debug() {
        return parseBool(KEY_DEBUG);
    }

    /**
     * Whether the bot runs isolated on a private nested display. Unlike the other accessors this one has a
     * baked-in <b>default of {@code true}</b>: an absent or unparseable {@code session.isolated} key yields
     * {@link Boolean#TRUE}, so background isolation is the default everywhere and only an explicit
     * {@code session.isolated=false} opts back to the shared {@code :0}. Never {@code null}.
     */
    public static Boolean sessionIsolated() {
        Boolean parsed = parseBool(KEY_SESSION_ISOLATED);
        return parsed == null ? Boolean.TRUE : parsed;
    }

    /** The explicit backend override ({@code gamescope}/{@code xephyr}), or {@code null} to let the kind pick. */
    public static String sessionBackend() {
        return get(KEY_SESSION_BACKEND);
    }

    /**
     * Parses {@code key} as a boolean: {@code true}/{@code 1}/{@code yes}/{@code on} → {@link Boolean#TRUE};
     * {@code false}/{@code 0}/{@code no}/{@code off} → {@link Boolean#FALSE}; absent or unrecognised →
     * {@code null} (the caller supplies its own default).
     */
    private static Boolean parseBool(String key) {
        String spec = get(key);
        if (spec == null) {
            return null;
        }
        return switch (spec.toLowerCase()) {
            case "true", "1", "yes", "on" -> Boolean.TRUE;
            case "false", "0", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * The project's default capture resolution (the resolution its templates were authored at), or
     * {@code null} when unset. Used by the matcher to rescale a live capture taken at a different
     * resolution before template matching.
     */
    public static Dimension defaultResolution() {
        String w = get(KEY_CAPTURE_WIDTH);
        String h = get(KEY_CAPTURE_HEIGHT);
        if (w == null || h == null) {
            return null;
        }
        try {
            int width = Integer.parseInt(w);
            int height = Integer.parseInt(h);
            if (width > 0 && height > 0) {
                return new Dimension(width, height);
            }
        } catch (NumberFormatException ignored) {
            // unparseable — treat as unset
        }
        return null;
    }
}
