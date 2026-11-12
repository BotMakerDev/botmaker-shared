package com.botmaker.shared.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void anUnreadableConfigFileIsNeitherMergedOverNorIsTheCacheFileDeleted(@TempDir Path cache, @TempDir Path config)
            throws IOException {
        Path old = Files.writeString(cache.resolve("credentials.json"), "{\"github_token\":\"old\"}");
        Path now = Files.writeString(config.resolve("credentials.json"), "{\"google_token\": broken");

        Credentials.moveOver(old, now);

        assertEquals("{\"google_token\": broken", Files.readString(now));
        assertEquals("{\"github_token\":\"old\"}", Files.readString(old));
    }

    @Test
    void anUnreadableCacheFileIsLeftWhereItIs(@TempDir Path cache, @TempDir Path config) throws IOException {
        Path old = Files.writeString(cache.resolve("credentials.json"), "not json");
        Path now = Files.writeString(config.resolve("credentials.json"), "{\"github_token\":\"new\"}");

        Credentials.moveOver(old, now);

        assertTrue(Files.exists(old));
        assertEquals("new", Credentials.read(now, "github_token"));
    }

    @Test
    void anUnreadableCacheFileIsNotMovedWhereNothingIs(@TempDir Path cache, @TempDir Path config) throws IOException {
        Path old = Files.writeString(cache.resolve("credentials.json"), "{\"github_token\": tor");
        Path now = config.resolve("credentials.json");

        Credentials.moveOver(old, now);

        assertTrue(Files.exists(old));
        assertFalse(Files.exists(now));
    }

    @Test
    void aStoreOrRemoveOverAnUnreadableFileChangesNothingAndLogsNoneOfItsText(@TempDir Path config)
            throws IOException {
        for (String text : List.of("[\"not an object\"]", "{\"github_token\": \"secret-ish",
                "{\"google_play_aas\":{\"token\":\"t\"}}")) {
            Path file = Files.writeString(config.resolve("credentials.json"), text);

            String log = stderrOf(() -> {
                Credentials.store(file, "github_token", "gh");
                Credentials.remove(file, "google_play_aas");
            });

            assertEquals(text, Files.readString(file));
            assertTrue(log.contains("can't be read"), log);
            assertFalse(log.contains("secret-ish") || log.contains("\"t\""), log);
        }
    }

    @Test
    void aFileHoldingNullIsEmptyAndWritable(@TempDir Path config) throws IOException {
        Path file = Files.writeString(config.resolve("credentials.json"), "null");

        Credentials.store(file, "github_token", "gh");

        assertEquals("gh", Credentials.read(file, "github_token"));
        assertFalse(Files.exists(config.resolve("credentials.json.tmp")));
    }

    private static String stderrOf(Runnable run) {
        PrintStream was = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            run.run();
        } finally {
            System.setErr(was);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void nothingToMoveTouchesNothing(@TempDir Path cache, @TempDir Path config) {
        Path now = config.resolve("credentials.json");
        Credentials.moveOver(cache.resolve("credentials.json"), now);
        assertFalse(Files.exists(now));
    }
}
