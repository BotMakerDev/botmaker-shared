package com.botmaker.shared.emulator.tree;

import com.botmaker.shared.emulator.AdbDevice;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Opens the {@link TreeReader} for a device: {@code u2.jar} while it works, Android's {@code uiautomator dump} once
 * it can't. {@code u2.jar} starts on the first read; a read that fails restarts it once in the reader's life, and a
 * second failure, or a start that fails, leaves the dump for good.
 */
public final class TreeReaders {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);

    private TreeReaders() {}

    /** The reader for {@code device}. Starts nothing until the first {@link TreeReader#read()}; never throws. */
    public static TreeReader open(AdbDevice device) {
        return open(TreeDevice.of(device), START_TIMEOUT);
    }

    static TreeReader open(TreeDevice device, Duration startTimeout) {
        return new Fallback(new U2Reader(device, startTimeout), new DumpReader(device));
    }

    /** {@code u2.jar} first, the dump after it gives up. */
    private static final class Fallback implements TreeReader {
        private U2Reader u2;
        private final DumpReader dump;
        /** Whether u2 ever gave a tree: only a server that worked is worth a restart. */
        private boolean answered;
        private boolean restarted;
        private boolean closed;

        Fallback(U2Reader u2, DumpReader dump) {
            this.u2 = u2;
            this.dump = dump;
        }

        @Override
        public synchronized Optional<UiTree> read() {
            if (closed) return Optional.empty();
            if (u2 != null) {
                try {
                    if (!u2.running()) u2.start();
                    UiTree tree = u2.read();
                    answered = true;
                    return Optional.of(tree);
                } catch (IOException | RuntimeException failed) {
                    // a RuntimeException is a shell the device stopped answering
                    Optional<UiTree> again = restartOnce();
                    if (again.isPresent()) return again;
                }
                u2.abandon();
                u2 = null;
            }
            return dump.read();
        }

        /** A server that ran and stopped answering is started again, once; one that never started is not. */
        private Optional<UiTree> restartOnce() {
            if (restarted || !answered) return Optional.empty();
            restarted = true;
            u2.stop();
            try {
                u2.start();
                return Optional.of(u2.read());
            } catch (IOException | RuntimeException failedAgain) {
                return Optional.empty();
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (u2 != null) u2.stop();
            u2 = null;
        }
    }
}
