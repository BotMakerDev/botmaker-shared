package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.config.AndroidState;
import com.botmaker.shared.emulator.tree.TreeReader;
import com.botmaker.shared.emulator.tree.TreeReaders;
import com.botmaker.shared.emulator.tree.UiTree;

import java.awt.Rectangle;
import java.util.Optional;
import java.util.Set;

/**
 * What a sign-in or sign-out does to an instance: read its screen, tap, type, press a key, run a command and list its
 * Google accounts. {@link #of} is a running instance's; tests give their own.
 */
public interface AccountDevice extends AutoCloseable {

    /** The screen now, empty when Android did not answer. */
    Optional<UiTree> screen();

    void tap(int x, int y);

    /**
     * Types {@code text} into the focused field. Its step says the length only ({@link AdbDevice#text}); the caller
     * clears the array.
     */
    void type(char[] text);

    void key(int keyCode);

    void shell(String command);

    /** The Google emails signed in, lower case, or null when Android did not say. */
    Set<String> accounts();

    @Override
    void close();

    /** Taps the middle of {@code bounds}. */
    default void tap(Rectangle bounds) {
        tap((int) bounds.getCenterX(), (int) bounds.getCenterY());
    }

    /**
     * The running {@code instance}, over one adb connection and one tree reader, both closed with it. While it is open
     * the instance is {@linkplain SignInsRunning held}, which the core's {@code device} tool respects, and its log is
     * cleared as it closes.
     */
    static AccountDevice of(EmulatorInstance instance) {
        // held from before the first screen to after the last: a password may be on any of them
        SignInsRunning.Hold hold = SignInsRunning.hold(instance);
        AdbDevice device;
        TreeReader reader;
        try {
            device = AdbDevice.connect(instance.adb());
            try {
                reader = TreeReaders.open(device);
            } catch (RuntimeException | Error e) {
                device.close();
                throw e;
            }
        } catch (RuntimeException | Error e) {
            hold.close();
            throw e;
        }
        return new AccountDevice() {
            @Override
            public Optional<UiTree> screen() {
                return reader.read();
            }

            @Override
            public void tap(int x, int y) {
                device.tap(x, y);
            }

            @Override
            public void type(char[] text) {
                // input text takes a String: it lives until collected, the array is what the batch clears
                device.text(new String(text));
            }

            @Override
            public void key(int keyCode) {
                device.key(keyCode);
            }

            @Override
            public void shell(String command) {
                device.shell(command);
            }

            @Override
            public Set<String> accounts() {
                try {
                    return AndroidState.googleAccounts(device.shell(AndroidState.ACCOUNTS));
                } catch (RuntimeException | Error e) {
                    // dadb surfaces some failures as Errors; either way Android did not say
                    return null;
                }
            }

            @Override
            public void close() {
                try {
                    reader.close();
                } finally {
                    try {
                        // what was logged while a password was typed is not left for the next reader of the log
                        try {
                            device.shell("logcat -c");
                        } catch (RuntimeException | Error uncleared) {
                            // the instance is gone, and its log with it
                        }
                        device.close();
                    } finally {
                        hold.close();
                    }
                }
            }
        };
    }
}
