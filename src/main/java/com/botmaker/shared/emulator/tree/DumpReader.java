package com.botmaker.shared.emulator.tree;

import java.util.Optional;

/**
 * Reads the tree with Android's own {@code uiautomator dump}: nothing to push or start, but about 2 s a read, and it
 * waits for the screen to be idle, so a looping animation can make it fail. The fallback when {@link U2Reader}
 * cannot run.
 */
final class DumpReader implements TreeReader {

    private static final String END = "</hierarchy>";

    private final TreeDevice device;

    DumpReader(TreeDevice device) {
        this.device = device;
    }

    @Override
    public Optional<UiTree> read() {
        try {
            String out = device.shell("uiautomator dump /dev/tty");
            // The XML is followed by "UI hierchary dumped to: /dev/tty" (Android's spelling).
            int end = out.lastIndexOf(END);
            if (end < 0) return Optional.empty();
            return Optional.of(UiTree.parse(out.substring(0, end + END.length())));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        // nothing started
    }
}
