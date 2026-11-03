package com.botmaker.shared.runlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunFilesTest {

    @Test
    void aRunCopysTagAndCommitComeFromItsBotJson(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("bot.json"), "{\"tag\":\"v1.2.0\",\"commit\":\"abc123\",\"libs\":[]}");
        assertEquals(Map.of("tag", "v1.2.0", "commit", "abc123"), RunFiles.source(dir));
    }

    @Test
    void aFolderWithNeitherBotJsonNorGitSaysNothing(@TempDir Path dir) {
        assertTrue(RunFiles.source(dir).isEmpty());
    }

    @Test
    void theCoordinateIsTheBotsNotItsParents(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><parent><groupId>com.github.BotMakerDev</groupId>
                <artifactId>botmaker-plugin-parent</artifactId></parent>
                <groupId>com.example</groupId><artifactId>farm-bot</artifactId></project>""");
        assertEquals("com.example:farm-bot", RunFiles.coordinate(dir));
    }
}
