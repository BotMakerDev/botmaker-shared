package com.botmaker.shared.tools;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * <b>Where BotMaker keeps things on this machine</b> — two directories, and the distinction between them is
 * the whole reason this class exists rather than one {@code dir()}.
 *
 * <ul>
 *   <li>{@link #config()} holds what the user <em>told</em> us and nothing can rebuild: the saved phone
 *       addresses of {@code SavedDevices}, first of all. Deleting it loses data.</li>
 *   <li>{@link #cache()} holds what we can always fetch again: the downloaded {@code adb} and
 *       {@code scrcpy-server} of {@link ManagedTools}. Deleting it costs a download.</li>
 * </ul>
 *
 * <p>Putting a downloaded tool in config would make a cache-cleaner unable to reclaim 16 MB; putting a saved
 * address in cache would make one delete the user's phones. Each of the two features that needed a directory
 * had started to answer this for itself — {@code SavedDevices.configDir} and Studio's
 * {@code BotMakerDirs.getCacheDir} — and the per-OS layouts here are those two, single-sourced.
 *
 * <p>Both are overridable by a system property, which is what tests use: nothing here is allowed to write to a
 * real user's directories during a build.
 */
public final class UserDirs {

    private UserDirs() {}

    /** Overrides {@link #config()} outright. */
    public static final String CONFIG_PROPERTY = "botmaker.config.dir";

    /** Overrides {@link #cache()} outright. */
    public static final String CACHE_PROPERTY = "botmaker.cache.dir";

    /** The per-user config directory: durable, user-owned, never auto-cleaned. */
    public static Path config() {
        Path override = override(CONFIG_PROPERTY);
        return override != null ? override
                : config(System.getProperty("os.name", ""), System::getenv,
                        System.getProperty("user.home", "."));
    }

    /** The per-user cache directory: everything in it is re-derivable by definition. */
    public static Path cache() {
        Path override = override(CACHE_PROPERTY);
        return override != null ? override
                : cache(System.getProperty("os.name", ""), System::getenv,
                        System.getProperty("user.home", "."));
    }

    /**
     * The config layout for one OS. Package-visible and taking its whole environment as arguments so all three
     * platforms are asserted from any one of them — the alternative is a test that only checks the OS it runs
     * on, which for a per-OS switch is the one thing worth not doing.
     */
    static Path config(String osName, UnaryOperator<String> env, String home) {
        if (windows(osName)) {
            String local = env.apply("LOCALAPPDATA");
            return blank(local) ? Path.of(home, "BotMaker") : Path.of(local, "BotMaker");
        }
        if (mac(osName)) {
            return Path.of(home, "Library", "Application Support", "botmaker");
        }
        String xdg = env.apply("XDG_CONFIG_HOME");
        return blank(xdg) ? Path.of(home, ".config", "botmaker") : Path.of(xdg, "botmaker");
    }

    /** The cache layout for one OS. Same shape, and the same reason for taking its environment. */
    static Path cache(String osName, UnaryOperator<String> env, String home) {
        if (windows(osName)) {
            String local = env.apply("LOCALAPPDATA");
            // Windows has no separate cache root, so it is a subdirectory of the same BotMaker folder — the
            // layout Studio's BotMakerDirs already writes to, kept identical rather than improved on.
            return blank(local) ? Path.of(home, "BotMaker", ".cache") : Path.of(local, "BotMaker", ".cache");
        }
        if (mac(osName)) {
            return Path.of(home, "Library", "Caches", "botmaker");
        }
        String xdg = env.apply("XDG_CACHE_HOME");
        return blank(xdg) ? Path.of(home, ".cache", "botmaker") : Path.of(xdg, "botmaker");
    }

    private static Path override(String property) {
        String value = System.getProperty(property);
        return blank(value) ? null : Path.of(value);
    }

    private static boolean windows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean mac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
