package com.botmaker.shared.input;

import com.botmaker.shared.input.linux.X11InputListener;
import com.sun.jna.Platform;

/**
 * Picks an {@link InputListener} implementation by OS. Only X11/Linux is supported today (via XRecord);
 * elsewhere {@link #isSupported()} is false and {@link #create()} throws — consumers (e.g. the Studio macro
 * recorder) gate the feature on {@link #isSupported()}.
 *
 * <p>Unlike {@link com.botmaker.shared.capture.NativeControllerFactory}, listeners are <b>not</b> cached:
 * each is a disposable session that owns its own X display connections, so {@link #create()} returns a fresh
 * instance the caller {@code close()}s when done.
 */
public final class InputListenerFactory {

	private InputListenerFactory() {}

	/** True when a global input listener is available on this platform (currently Linux/X11 only). */
	public static boolean isSupported() {
		return Platform.isLinux();
	}

	/** Creates a fresh, un-started listener. Throws {@link UnsupportedOperationException} off Linux. */
	public static InputListener create() {
		if (Platform.isLinux()) {
			return new X11InputListener();
		}
		throw new UnsupportedOperationException("Global input recording is only supported on Linux/X11.");
	}
}
