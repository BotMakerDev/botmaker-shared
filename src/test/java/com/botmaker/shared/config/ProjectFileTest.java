package com.botmaker.shared.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The directory-shaped reader of {@code botmaker-project.properties}, and chiefly the answers it gives when
 * the file is not there or does not say what it should.
 *
 * <p>Those are the load-bearing cases: three modules used to keep their own copy of this read, and what they
 * disagreed about was never the happy path — it was whether a missing file, a blank value or a monitor index
 * that is not a number should be an exception, a zero, or the caller's own default.
 */
class ProjectFileTest {

    @Test
    void everythingAnswersACallersOwnDefaultWhenThereIsNoFile(@TempDir Path dir) {
        assertTrue(ProjectFile.read(dir).isEmpty());
        assertNull(ProjectFile.value(dir, ProjectProperties.KEY_CAPTURE_SOURCE));
        assertNull(ProjectFile.captureSource(dir));
        assertNull(ProjectFile.launchTarget(dir));
        assertNull(ProjectFile.captureSize(dir));
        assertTrue(ProjectFile.sessionIsolated(dir), "isolation is on unless the project turns it off");
    }

    @Test
    void aNullDirectoryIsTheSameAsAMissingFile() {
        assertTrue(ProjectFile.read(null).isEmpty());
        assertNull(ProjectFile.captureSource(null));
        assertTrue(ProjectFile.sessionIsolated(null));
    }

    @Test
    void valuesAreTrimmedAndABlankOneReadsAsAbsent(@TempDir Path dir) throws IOException {
        write(dir, """
                capture.source =   window:Diablo IV\s
                launch.target =\s
                """);

        assertEquals("window:Diablo IV", ProjectFile.captureSource(dir));
        assertNull(ProjectFile.launchTarget(dir));
    }

    @Test
    void aCaptureSizeNeedsBothHalvesAndBothPositive(@TempDir Path dir) throws IOException {
        write(dir, "capture.width=1280\ncapture.height=720\n");
        assertEquals(1280, ProjectFile.captureSize(dir).width);
        assertEquals(720, ProjectFile.captureSize(dir).height);

        write(dir, "capture.width=1280\n");
        assertNull(ProjectFile.captureSize(dir), "half a size is no size");

        write(dir, "capture.width=wide\ncapture.height=720\n");
        assertNull(ProjectFile.captureSize(dir), "a hand-edited value must not stop a launch");

        write(dir, "capture.width=0\ncapture.height=720\n");
        assertNull(ProjectFile.captureSize(dir));
    }

    @Test
    void onlyAnExplicitlyOffValueTurnsIsolationOff(@TempDir Path dir) throws IOException {
        for (String off : new String[] {"false", "FALSE", "0", "no", "off"}) {
            write(dir, "session.isolated=" + off + "\n");
            assertFalse(ProjectFile.sessionIsolated(dir), off + " should read as off");
        }
        write(dir, "session.isolated=maybe\n");
        assertTrue(ProjectFile.sessionIsolated(dir), "an unreadable value leaves the default alone");
    }

    private static void write(Path dir, String contents) throws IOException {
        Files.writeString(dir.resolve(ProjectProperties.FILE_NAME), contents);
    }
}
