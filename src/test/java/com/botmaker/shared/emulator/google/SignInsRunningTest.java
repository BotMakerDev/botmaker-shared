package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.config.InstanceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInsRunningTest {

    private static final InstanceKey FARM = new InstanceKey(PlatformId.LDPLAYER, "Farm 1");
    private static final InstanceKey OTHER = new InstanceKey(PlatformId.MUMU, "Farm 1");

    @Test
    void anInstanceIsHeldUntilEveryHoldOnItIsClosed(@TempDir Path dir) {
        assertFalse(SignInsRunning.on(FARM, dir));
        SignInsRunning.Hold first = SignInsRunning.hold(FARM, dir);
        SignInsRunning.Hold second = SignInsRunning.hold(FARM, dir);
        assertTrue(SignInsRunning.on(FARM, dir));
        assertFalse(SignInsRunning.on(OTHER, dir), "another emulator's instance of that name is not held");
        first.close();
        first.close();
        assertTrue(SignInsRunning.on(FARM, dir), "closing one hold twice releases it once");
        second.close();
        assertFalse(SignInsRunning.on(FARM, dir));
    }

    @Test
    void theFileNamesTheProcessAndGoesWithTheHold(@TempDir Path dir) throws IOException {
        try (SignInsRunning.Hold ignored = SignInsRunning.hold(FARM, dir)) {
            try (var files = Files.list(dir)) {
                Path file = files.findFirst().orElseThrow();
                assertEquals(SignInsRunning.stamp(ProcessHandle.current()), Files.readString(file));
            }
            assertTrue(SignInsRunning.any(dir));
        }
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
        assertFalse(SignInsRunning.any(dir));
        assertEquals("ldplayer%2FFarm%2A1.pid", SignInsRunning.name(new InstanceKey(PlatformId.LDPLAYER, "Farm*1")),
                "a name Windows takes as a file's");
    }

    @Test
    void anotherLiveProcessHoldsAndADeadOrCrashedOneDoesNot(@TempDir Path dir) throws IOException {
        Path file;
        try (SignInsRunning.Hold ignored = SignInsRunning.hold(FARM, dir); var files = Files.list(dir)) {
            file = files.findFirst().orElseThrow();
        }
        ProcessHandle parent = ProcessHandle.current().parent().orElseThrow();
        Files.writeString(file, SignInsRunning.stamp(parent));
        assertTrue(SignInsRunning.on(FARM, dir), "a sign-in in another BotMaker process");
        assertTrue(Files.exists(file));
        Files.writeString(file, parent.pid() + "@1");
        assertFalse(SignInsRunning.on(FARM, dir), "that pid is another process's now");
        assertFalse(Files.exists(file), "a file that holds nothing is cleared");
        Files.writeString(file, SignInsRunning.stamp(ProcessHandle.current()));
        assertFalse(SignInsRunning.on(FARM, dir), "this process holds nothing: the file was left behind");
        Files.writeString(file, Long.MAX_VALUE + "@1");
        assertFalse(SignInsRunning.on(FARM, dir), "its process is gone");
        Files.writeString(file, "not a pid");
        assertFalse(SignInsRunning.on(FARM, dir));
    }
}
