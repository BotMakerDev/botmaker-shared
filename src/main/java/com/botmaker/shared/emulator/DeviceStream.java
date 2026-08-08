package com.botmaker.shared.emulator;

import dadb.AdbStream;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A duplex byte stream to one ADB service, as plain {@link InputStream}/{@link OutputStream}.
 *
 * <p>It exists so that {@code dadb} types stop at this package. Everything in {@code emulator/} is built out
 * of one-shot commands, but a streaming channel ({@code com.botmaker.shared.device}) needs the raw socket for
 * as long as it lives — and handing it a {@code dadb.AdbStream} would spread the dependency to a second
 * package for no gain, since all it wants is two streams and a close.
 *
 * <p><b>Read it or wedge it.</b> ADB windows each stream separately and dadb only acknowledges bytes a caller
 * has actually taken, so a stream nobody reads stops being acknowledged and eventually stalls the connection
 * it shares. Anything opened here must be drained — including a process's stdout that is of no interest,
 * which is a discard loop, not a stream left alone.
 */
public final class DeviceStream implements AutoCloseable {

    private final AdbStream stream;

    DeviceStream(AdbStream stream) {
        this.stream = stream;
    }

    /** Bytes from the service. */
    public InputStream in() {
        return stream.getSource().inputStream();
    }

    /** Bytes to the service. Buffered by okio, so a write is not on the wire until it is flushed. */
    public OutputStream out() {
        return stream.getSink().outputStream();
    }

    @Override
    public void close() {
        try {
            stream.close();
        } catch (Exception ignored) {
            // Closing a stream whose connection already failed is expected to fail too.
        }
    }
}
