package com.botmaker.shared.capture;

import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * Frames of one place read again and again into a {@link FrameSink}, what makes each read kept open between them: on
 * X11 the window's composite pixmap and a shared-memory segment the server writes into, so a frame crosses no socket
 * (2026-10-22, as OBS reads a window). Used by one thread at a time; {@link #close()} frees what it holds.
 */
@FunctionalInterface
public interface FrameGrab extends AutoCloseable {

    /** The current frame written into {@code sink}; false when none could be read (the window gone or hidden). */
    boolean grab(FrameSink sink);

    @Override
    default void close() {
    }

    /** A grab of each frame {@code reads} makes, copied into the sink; nothing kept between reads. */
    static FrameGrab of(Supplier<BufferedImage> reads) {
        return sink -> {
            BufferedImage frame = reads.get();
            if (frame == null || frame.getWidth() == 0 || frame.getHeight() == 0) return false;
            FrameSink.write(frame, sink);
            return true;
        };
    }
}
