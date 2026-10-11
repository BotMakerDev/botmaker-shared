package com.botmaker.shared.input;

import com.botmaker.shared.input.linux.X11InputListener;
import com.botmaker.shared.input.mac.MacInputListener;
import com.botmaker.shared.platform.Os;

/**
 * Picks an {@link InputListener} implementation by OS: X11 XRecord on Linux, a CoreGraphics event tap on macOS.
 * Elsewhere {@link #isSupported()} is false and {@link #create()} throws — consumers (e.g. the Studio macro
 * recorder) gate the feature on {@link #isSupported()}.
 *
 * <p>Unlike {@link com.botmaker.shared.capture.NativeControllerFactory}, listeners are <b>not</b> cached:
 * each is a disposable session that owns its own native resources (X display connections, an event tap), so
 * {@link #create()} returns a fresh instance the caller {@code close()}s when done.
 */
public final class InputListenerFactory {

    private InputListenerFactory() {}

    /** True when a global input listener is available on this platform (Linux/X11 and macOS). */
    public static boolean isSupported() {
        return Os.current().isLinux() || Os.current().isMac();
    }

    /**
     * Creates a fresh, un-started listener. Throws {@link UnsupportedOperationException} where
     * {@link #isSupported()} is false.
     */
    public static InputListener create() {
        return switch (Os.current()) {
            case LINUX -> new X11InputListener();
            case MAC -> new MacInputListener();
            case WINDOWS, UNKNOWN -> throw new UnsupportedOperationException(
                    "Global input recording is not supported on " + Os.current().displayName() + ".");
        };
    }
}
