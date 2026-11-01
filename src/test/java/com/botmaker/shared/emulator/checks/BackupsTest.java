package com.botmaker.shared.emulator.checks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which backup the next Undo restores. */
class BackupsTest {

    @Test
    void theNewestNotUndoneOne(@TempDir Path folder) throws IOException {
        Files.createDirectory(folder.resolve("20261101-120000"));
        Files.createDirectory(folder.resolve("20261102-090000"));
        Files.createDirectory(folder.resolve("20261103-090000-undone"));
        Files.createDirectory(folder.resolve("notes"));
        Files.writeString(folder.resolve("20261104-090000"), "a file, not a backup");
        assertEquals(Optional.of(folder.resolve("20261102-090000")), Backups.newest(folder));
    }

    @Test
    void noneWhenAbsentOrAllUndone(@TempDir Path folder) throws IOException {
        assertEquals(Optional.empty(), Backups.newest(folder.resolve("missing")));
        Files.createDirectory(folder.resolve("20261103-090000-undone"));
        assertEquals(Optional.empty(), Backups.newest(folder));
    }

    @Test
    void aStampIsWhatTheFolderIsNamedBy() {
        assertEquals("20261102-090507", Backups.stamp(LocalDateTime.of(2026, 11, 2, 9, 5, 7)));
    }
}
