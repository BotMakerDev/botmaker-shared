package com.botmaker.shared.config;

import java.nio.file.Path;

/**
 * Where BotMaker keeps what it can afford to lose — one answer, for every process that has one.
 *
 * <p>It was Studio's ({@code studio.config.BotMakerDirs}) until 2026-08-30, which was right while Studio was
 * the only thing with a cache. It is not: an emulator's installed-app list is cached by
 * {@link com.botmaker.shared.emulator.EmulatorAppCache}, which is shared's now — reachable by a plugin and
 * by the {@code botmaker} CLI, neither of which may name a Studio type. A second copy of this three-branch
 * platform switch would be a second cache directory on somebody's machine, silently.
 *
 * <p>Studio's own {@code BotMakerDirs} delegates here, so its other callers are untouched and there is still
 * exactly one directory.
 */
public final class CacheDirs {

    private CacheDirs() {}

    /**
     * The per-user cache root, following each platform's own convention:
     * {@code %LOCALAPPDATA%\BotMaker\.cache} on Windows, {@code ~/Library/Caches/botmaker} on macOS, and
     * {@code $XDG_CACHE_HOME/botmaker} — falling back to {@code ~/.cache/botmaker} — elsewhere.
     */
    public static Path cacheRoot() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            return Path.of(System.getenv("LOCALAPPDATA"), "BotMaker", ".cache");
        } else if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Caches", "botmaker");
        } else {
            String xdgCache = System.getenv("XDG_CACHE_HOME");
            if (xdgCache != null && !xdgCache.isEmpty()) {
                return Path.of(xdgCache, "botmaker");
            }
            return Path.of(System.getProperty("user.home"), ".cache", "botmaker");
        }
    }
}
