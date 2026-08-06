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
 *   <li>the <b>bot tuning</b> keys the SDK's {@code api.BotSettings} reads on first use —
 *       {@link #KEY_CLICKS_FOUND_DELAY}, {@link #KEY_CLICKS_NOT_FOUND_DELAY}, {@link #KEY_CLICKS_RANDOMIZE},
 *       {@link #KEY_VISION_CONFIDENCE}, {@link #KEY_VISION_COMPARE_MARGIN}, {@link #KEY_BOT_MAX_RETRY_ATTEMPTS},
 *       {@link #KEY_INPUT_REAL} and {@link #KEY_INPUT_LINUX_BACKEND}</li>
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

    /**
     * The bot's runtime tuning — what used to be a generated {@code BotSettings.java} calling the SDK facade,
     * and is now eight keys in this file.
     *
     * <p>The generated class was a worse form of the same data: Studio wrote Java source and read its own
     * values back out with a per-statement regex, so the storage format was "whatever that parser still
     * recognises". These keys are read by {@code api.BotSettings} at first use, which is before the first
     * click — the ordering {@link #KEY_INPUT_REAL} depends on (see that key).
     */
    public static final String KEY_CLICKS_FOUND_DELAY = "clicks.foundDelay";
    public static final String KEY_CLICKS_NOT_FOUND_DELAY = "clicks.notFoundDelay";
    public static final String KEY_CLICKS_RANDOMIZE = "clicks.randomize";
    public static final String KEY_VISION_CONFIDENCE = "vision.confidence";
    public static final String KEY_VISION_COMPARE_MARGIN = "vision.compareMargin";
    public static final String KEY_BOT_MAX_RETRY_ATTEMPTS = "bot.maxRetryAttempts";

    /**
     * Whether the bot drives the <b>real</b> mouse and keyboard rather than posting quiet synthetic events.
     *
     * <p>It is the one setting here whose <em>timing</em> matters: on Linux it swaps the process-wide input
     * backend, one-way, and must happen before the first click or the click is silently dropped. That is why
     * {@code api.BotSettings} applies it as part of its lazy initialisation rather than leaving it to a
     * generated call the user could move.
     */
    public static final String KEY_INPUT_REAL = "input.real";

    /**
     * Which Linux backend delivers real input — one of
     * {@link com.botmaker.shared.capture.linux.input.LinuxInputBackendId}'s wire ids, or absent for
     * {@code LinuxController}'s own ladder. Read into the {@code botmaker.linux.input} system property the
     * controller consults, and therefore, like {@link #KEY_INPUT_REAL}, only meaningful before the first click.
     * The enum owns the accepted set; an unrecognised value here resolves to {@code auto} with a diagnostic
     * rather than silently reaching the cursor-safe backend.
     */
    public static final String KEY_INPUT_LINUX_BACKEND = "input.linuxBackend";

    private static volatile Properties cached;
    private static volatile boolean loaded;

    private ProjectProperties() {}

    /**
     * Test seam: inject the parsed properties directly, bypassing the one-time classpath load — mirrors
     * {@code NativeControllerFactory.setForTesting}. Pass {@code null} to reset back to the classpath source.
     *
     * <p>Public because the SDK's {@code api.BotSettings} seeds itself from these keys and its
     * <em>ordering</em> is what needs testing (the real-input swap must precede the first click), which cannot
     * be exercised from inside this module.
     */
    public static void setForTesting(Properties p) {
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

    /** The {@code emulator:} prefix of a {@link #KEY_CAPTURE_SOURCE} spec — see {@link #emulatorInstanceOf}. */
    public static final String EMULATOR_SOURCE_PREFIX = "emulator:";

    /**
     * The instance name in an {@code emulator:<instanceName>} capture-source spec, or {@code null} for any
     * other form ({@code desktop}, {@code monitor:<i>}, {@code window:<t>}, an empty name, {@code null}).
     *
     * <p>The prefix is defined by this class's key, but was being re-spelled as a literal wherever someone
     * needed to write or recognise one — Studio's launch-target dialog builds the spec, its capture-expression
     * codegen reads it, and the pilot now routes on it. A total parse next to the key keeps those from
     * drifting. (The other three forms still spell themselves; a sealed spec type for the whole grammar is
     * the eventual answer and is not this.)
     */
    public static String emulatorInstanceOf(String captureSource) {
        if (captureSource == null) {
            return null;
        }
        String spec = captureSource.trim();
        if (!spec.startsWith(EMULATOR_SOURCE_PREFIX)) {
            return null;
        }
        String name = spec.substring(EMULATOR_SOURCE_PREFIX.length()).trim();
        return name.isEmpty() ? null : name;
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

    /** Pause after a successful match, in ms, or {@code null} when unset — see {@link #KEY_CLICKS_FOUND_DELAY}. */
    public static Integer clicksFoundDelay() {
        return nonNegative(parseInt(KEY_CLICKS_FOUND_DELAY));
    }

    /** Pause after a failed match, in ms, or {@code null} when unset. */
    public static Integer clicksNotFoundDelay() {
        return nonNegative(parseInt(KEY_CLICKS_NOT_FOUND_DELAY));
    }

    /** Whether clicks land on a random point inside the match rather than its centre; {@code null} when unset. */
    public static Boolean clicksRandomize() {
        return parseBool(KEY_CLICKS_RANDOMIZE);
    }

    /** Default template-match confidence (0..1), or {@code null} when unset/out of range. */
    public static Double visionConfidence() {
        return inRange(parseDouble(KEY_VISION_CONFIDENCE), 0.0, 1.0);
    }

    /** Default compare margin (0..1) a good template must beat a distractor by, or {@code null} when unset. */
    public static Double visionCompareMargin() {
        return inRange(parseDouble(KEY_VISION_COMPARE_MARGIN), 0.0, 1.0);
    }

    /** How many no-progress checks the watchdog tolerates (at least 1), or {@code null} when unset. */
    public static Integer botMaxRetryAttempts() {
        Integer parsed = parseInt(KEY_BOT_MAX_RETRY_ATTEMPTS);
        return parsed == null || parsed < 1 ? null : parsed;
    }

    /** Whether to drive the real mouse and keyboard; {@code null} when unset — see {@link #KEY_INPUT_REAL}. */
    public static Boolean inputReal() {
        return parseBool(KEY_INPUT_REAL);
    }

    /** The pinned Linux input backend id, or {@code null} to let the controller choose. */
    public static String inputLinuxBackend() {
        return get(KEY_INPUT_LINUX_BACKEND);
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
     * Parses {@code key} as an {@code int}, or {@code null} when absent/unparseable.
     *
     * <p>Out-of-domain values are filtered by the accessors ({@link #nonNegative}, {@link #inRange}) rather than
     * here, and they filter to {@code null} — the caller's own default — because the SDK setters these feed
     * <em>throw</em> on a bad value. A hand-typed {@code vision.confidence=5} must leave the bot on its default,
     * not fail its first vision call with an exception raised inside a static initialiser.
     */
    private static Integer parseInt(String key) {
        String spec = get(key);
        if (spec == null) {
            return null;
        }
        try {
            return Integer.valueOf(spec);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses {@code key} as a {@code double}, or {@code null} when absent/unparseable. See {@link #parseInt}. */
    private static Double parseDouble(String key) {
        String spec = get(key);
        if (spec == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(spec);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private static Double inRange(Double value, double min, double max) {
        return value == null || value < min || value > max ? null : value;
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
