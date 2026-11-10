package com.botmaker.shared.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sign-ins file: keys merged on every write, and moved from the cache dir to the config dir once. */
class CredentialsTest {

    @Test
    void eachWriteKeepsTheOtherKeysAndTheLastRemovalDeletesTheFile(@TempDir Path config) {
        Path file = config.resolve("credentials.json");
        Credentials.store(file, "github_token", "gh");
        Credentials.store(file, "google_play_email", "alt@example.com");

        assertEquals("gh", Credentials.read(file, "github_token"));
        Credentials.remove(file, "github_token");
        assertEquals("alt@example.com", Credentials.read(file, "google_play_email"));
        Credentials.remove(file, "google_play_email");
        assertFalse(Files.exists(file));
    }

    @Test
    void theCacheDirsFileIsMovedToTheConfigDir(@TempDir Path cache, @TempDir Path home) throws IOException {
        Path old = cache.resolve("credentials.json");
        Files.writeString(old, "{\"github_token\":\"gh\"}");
        Path now = home.resolve("BotMaker").resolve("credentials.json");

        Credentials.moveOver(old, now);

        assertFalse(Files.exists(old));
        assertEquals("gh", Credentials.read(now, "github_token"));
    }

    @Test
    void withBothTheConfigDirsKeysWinAndNoneIsLost(@TempDir Path cache, @TempDir Path config) throws IOException {
        Path old = Files.writeString(cache.resolve("credentials.json"),
                "{\"github_token\":\"old\",\"google_token\":\"g\"}");
        Path now = Files.writeString(config.resolve("credentials.json"), "{\"github_token\":\"new\"}");

        Credentials.moveOver(old, now);

        assertFalse(Files.exists(old));
        assertEquals("new", Credentials.read(now, "github_token"));
        assertEquals("g", Credentials.read(now, "google_token"));
        assertTrue(Files.isRegularFile(now));
    }

    @Test
    void nothingToMoveTouchesNothing(@TempDir Path cache, @TempDir Path config) {
        Path now = config.resolve("credentials.json");
        Credentials.moveOver(cache.resolve("credentials.json"), now);
        assertFalse(Files.exists(now));
    }
}
