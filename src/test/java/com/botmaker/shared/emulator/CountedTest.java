package com.botmaker.shared.emulator;

import okio.Buffer;
import okio.Sink;
import okio.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountedTest {

    @Test
    void aReadFileIsToldUpToItsLength(@TempDir Path dir) throws IOException {
        File file = Files.write(dir.resolve("a.bin"), new byte[100_000]).toFile();
        List<long[]> told = new CopyOnWriteArrayList<>();
        try (Source source = Counted.source(file, (done, total) -> told.add(new long[]{done, total}))) {
            Buffer buffer = new Buffer();
            while (source.read(buffer, 8192) != -1) buffer.clear();
        }
        assertTrue(told.size() >= 2, "the first block and the end");
        assertEquals(100_000, told.getLast()[0]);
        assertEquals(100_000, told.getLast()[1]);
        assertEquals(1, told.stream().filter(one -> one[0] == 100_000).count(), "the end told once");
    }

    @Test
    void aWrittenFileIsToldAgainstItsTotalOrNotAtAll(@TempDir Path dir) throws IOException {
        List<Long> told = new CopyOnWriteArrayList<>();
        try (Sink sink = Counted.sink(dir.resolve("b.bin").toFile(), 10, (done, total) -> told.add(done))) {
            sink.write(new Buffer().write(new byte[10]), 10);
        }
        assertEquals(10L, told.getLast());
        assertEquals(10, Files.size(dir.resolve("b.bin")));

        told.clear();
        try (Sink sink = Counted.sink(dir.resolve("c.bin").toFile(), -1, (done, total) -> told.add(done))) {
            sink.write(new Buffer().write(new byte[10]), 10);
        }
        assertTrue(told.isEmpty());
    }
}
