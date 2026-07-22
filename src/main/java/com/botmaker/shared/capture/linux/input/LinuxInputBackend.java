package com.botmaker.shared.capture.linux.input;

import com.sun.jna.Pointer;

/**
 * Pluggable input-synthesis strategy for the Linux backend, selected by the {@code botmaker.linux.input}
 * system property / env var (see {@link com.botmaker.shared.capture.linux.LinuxController}).
 *
 * <p>Backends fall into two families:
 * <ul>
 *   <li><b>Cursor-preserving</b> ({@link XSendEventBackend}) — delivers synthetic events straight to a target
 *       window without moving the real pointer, so a bot can click in the background. Only reaches
 *       X11/XWayland windows, and some toolkits reject synthetic events. {@link #preservesCursor()} is true.</li>
 *   <li><b>Cursor-moving</b> ({@link XTestBackend}, {@link UinputBackend}) — drives the one shared pointer.
 *       Reliable (uinput reaches everything incl. native Wayland + games) but visibly hijacks the cursor.
 *       {@link #preservesCursor()} is false; these are explicit opt-ins.</li>
 * </ul>
 *
 * <p>Keyboard methods take an <b>X keysym</b> (matching {@link com.botmaker.shared.capture.NativeController}'s
 * Linux key-code contract); each backend maps it to whatever its transport needs (X keycode, Linux KEY_*).
 * Coordinates are absolute screen pixels unless named relative.
 */
public interface LinuxInputBackend extends AutoCloseable {

	/** Short name for logging (e.g. {@code "xsendevent"}). */
	String name();

	/** True if this backend leaves the user's real cursor untouched. */
	boolean preservesCursor();

	/** Full left/other click delivered to {@code window} at window-relative coordinates. */
	void clickWindow(Pointer window, int relX, int relY, int button);

	/** Full click at absolute screen coordinates (backend resolves the target window if it needs one). */
	void clickScreen(int xAbs, int yAbs, int button);

	/** Position the (virtual or real) pointer at absolute screen coordinates. */
	void move(int xAbs, int yAbs);

	/** Press/release a mouse button at the current pointer position. 1=left, 2=middle, 3=right. */
	void button(int button, boolean press);

	/** Press/release a key given its X keysym (delivered to whatever currently holds focus). */
	void key(int keysym, boolean press);

	/**
	 * Press/release a key delivered to {@code window} specifically rather than the focused window — the
	 * keyboard analogue of {@link #clickWindow}. Only the cursor-preserving {@link XSendEventBackend}
	 * targets a window; the cursor-moving backends drive the one real device and have no per-window notion,
	 * so they keep the default (delegate to the focused-window {@link #key(int, boolean)} path).
	 */
	default void key(Pointer window, int keysym, boolean press) {
		key(keysym, press);
	}

	/** Scroll: {@code +} = up/away, {@code -} = down/toward. */
	void scroll(int amount);

	/** Release any native resources (e.g. a uinput device). Default: nothing. */
	@Override
	default void close() {}
}
