package com.botmaker.shared.emulator;

import com.botmaker.shared.steps.DeviceSteps;
import okio.Buffer;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import java.io.File;
import java.io.IOException;

/**
 * A file read into, or written from, dadb's push and pull (never its install, which copies a Source to a temp file on an
 * old Android) with its bytes told to a {@link DeviceSteps.Progress} as they pass: at most
 * twice a second, and once more at the end, so a long push or pull shows how far it is without an event per block.
 */
final class Counted {

    private static final long EVERY_NANOS = 500_000_000L;

    private final DeviceSteps.Progress progress;
    private final long total;
    private long done;
    private long toldAt;
    private long told = -1;

    private Counted(DeviceSteps.Progress progress, long total) {
        this.progress = progress;
        this.total = total;
        // nanoTime's origin is arbitrary, perhaps negative: due at once, measured from now.
        this.toldAt = System.nanoTime() - EVERY_NANOS;
    }

    /** {@code file} to read, its bytes told against its length. */
    static Source source(File file, DeviceSteps.Progress progress) throws IOException {
        Counted counted = new Counted(progress, file.length());
        return new ForwardingSource(Okio.source(file)) {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                long read = super.read(sink, byteCount);
                counted.passed(read < 0 ? 0 : read, read < 0);
                return read;
            }
        };
    }

    /** {@code file} to write, replacing it, its bytes told against {@code total}; nothing told when that is unknown. */
    static Sink sink(File file, long total, DeviceSteps.Progress progress) throws IOException {
        Counted counted = new Counted(total > 0 ? progress : DeviceSteps.Progress.NONE, total);
        return new ForwardingSink(Okio.sink(file)) {
            @Override
            public void write(Buffer source, long byteCount) throws IOException {
                super.write(source, byteCount);
                counted.passed(byteCount, false);
            }

            @Override
            public void close() throws IOException {
                super.close();
                counted.passed(0, true);
            }
        };
    }

    private void passed(long bytes, boolean last) {
        done += bytes;
        long now = System.nanoTime();
        // The end is read or closed more than once; told once.
        if (last ? done != told : now - toldAt >= EVERY_NANOS) {
            toldAt = now;
            told = done;
            progress.report(Math.min(done, total), total);
        }
    }
}
