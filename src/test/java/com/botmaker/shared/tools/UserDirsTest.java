package com.botmaker.shared.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The per-OS directory layouts, asserted for all three platforms from whichever one the build runs on —
 * which is the point of {@code config`/`cache` taking their environment as arguments rather than reading it.
 */
class UserDirsTest {

    private static final UnaryOperator<String> NO_ENV = name -> null;

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    @AfterEach
    void clearOverrides() {
        System.clearProperty(UserDirs.CONFIG_PROPERTY);
        System.clearProperty(UserDirs.CACHE_PROPERTY);
    }

    @Test
    void linuxHonoursXdgAndFallsBackToTheDotDirectories() {
        assertEquals(Path.of("/xdg/config/botmaker"),
                UserDirs.config("Linux", env(Map.of("XDG_CONFIG_HOME", "/xdg/config")), "/home/u"));
        assertEquals(Path.of("/home/u/.config/botmaker"), UserDirs.config("Linux", NO_ENV, "/home/u"));
        assertEquals(Path.of("/xdg/cache/botmaker"),
                UserDirs.cache("Linux", env(Map.of("XDG_CACHE_HOME", "/xdg/cache")), "/home/u"));
        assertEquals(Path.of("/home/u/.cache/botmaker"), UserDirs.cache("Linux", NO_ENV, "/home/u"));
    }

    @Test
    void macUsesApplicationSupportAndCaches() {
        assertEquals(Path.of("/Users/u/Library/Application Support/botmaker"),
                UserDirs.config("Mac OS X", NO_ENV, "/Users/u"));
        assertEquals(Path.of("/Users/u/Library/Caches/botmaker"),
                UserDirs.cache("Mac OS X", NO_ENV, "/Users/u"));
    }

    @Test
    void windowsUsesLocalAppDataAndSurvivesItBeingUnset() {
        Map<String, String> local = Map.of("LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local");
        assertEquals(Path.of("C:\\Users\\u\\AppData\\Local", "BotMaker"),
                UserDirs.config("Windows 11", env(local), "C:\\Users\\u"));
        assertEquals(Path.of("C:\\Users\\u\\AppData\\Local", "BotMaker", ".cache"),
                UserDirs.cache("Windows 11", env(local), "C:\\Users\\u"));
        assertEquals(Path.of("C:\\Users\\u", "BotMaker"),
                UserDirs.config("Windows 11", NO_ENV, "C:\\Users\\u"));
    }

    /**
     * The distinction the class exists for: a downloaded tool must never land where the saved phone list does,
     * or a cache-cleaner reclaiming disk would take the user's own data with it.
     */
    @Test
    void configAndCacheAreNeverTheSameDirectory() {
        for (String os : new String[] {"Linux", "Mac OS X", "Windows 11"}) {
            assertNotEquals(UserDirs.config(os, NO_ENV, "/home/u"), UserDirs.cache(os, NO_ENV, "/home/u"), os);
        }
    }

    @Test
    void aPropertyOverridesEverything() {
        System.setProperty(UserDirs.CONFIG_PROPERTY, "/tmp/cfg");
        System.setProperty(UserDirs.CACHE_PROPERTY, "/tmp/cache");
        assertEquals(Path.of("/tmp/cfg"), UserDirs.config());
        assertEquals(Path.of("/tmp/cache"), UserDirs.cache());
    }
}
