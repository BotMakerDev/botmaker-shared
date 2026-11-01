package com.botmaker.shared.emulator.checks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An Android Studio emulator's checks, over its {@code config.ini}. */
class AvdChecksTest {

    @Test
    void littleMemoryIsFixable() {
        CheckResult row = AvdChecks.memory("hw.ramSize=2048\n");
        assertEquals(CheckState.FAILED, row.state());
        assertTrue(row.fixable());
        assertEquals(CheckState.OK, AvdChecks.memory("hw.ramSize = 4G\n").state());
        assertEquals(CheckState.OK, AvdChecks.memory("hw.ramSize=8192M\n").state());
        assertNull(AvdChecks.memory("hw.lcd.width=1080\n"), "the emulator's own default");
        assertNull(AvdChecks.memory("hw.ramSize=4194304G\n"), "too large to read is no reading");
    }

    @Test
    void anImageWithoutGooglePlayIsTheUsersToReplace() {
        CheckResult row = AvdChecks.playImage("tag.id=google_apis\n");
        assertTrue(row.failed());
        assertFalse(row.fixable());
        assertTrue(row.remedy().contains("Google Play"));
        assertEquals(CheckState.OK, AvdChecks.playImage("PlayStore.enabled=true\n").state());
        assertEquals(CheckState.OK, AvdChecks.playImage("tag.id=google_apis_playstore\n").state());
        assertEquals(CheckState.INFO, AvdChecks.playImage("hw.ramSize=4096\n").state(), "says nothing about Play");
    }

    @Test
    void theConfigIsWhereTheIdsIniPoints(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve("Pixel.ini"), "avd.ini.encoding=UTF-8\npath=/elsewhere/Pixel.avd\n");
        assertEquals(Path.of("/elsewhere/Pixel.avd/config.ini"), AvdChecks.configIni(home, "Pixel"));
        assertEquals(home.resolve("Other.avd/config.ini"), AvdChecks.configIni(home, "Other"));
    }
}
