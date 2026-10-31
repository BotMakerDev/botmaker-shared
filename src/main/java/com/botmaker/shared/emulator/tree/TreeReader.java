package com.botmaker.shared.emulator.tree;

import java.util.Optional;

/** Reads an Android device's accessibility tree on demand. One per device; {@link TreeReaders#open} makes one. */
public interface TreeReader extends AutoCloseable {

    /**
     * The whole tree as the screen is now, every window included; empty when nothing on the device answered.
     * Never throws.
     */
    Optional<UiTree> read();

    /** Stops whatever the reader started on the device. Never throws; closing twice does nothing more. */
    @Override
    void close();
}
