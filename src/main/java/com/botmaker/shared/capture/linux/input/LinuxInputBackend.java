package com.botmaker.shared.capture.linux.input;

import com.sun.jna.Pointer;

import java.util.function.Supplier;

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

	/**
	 * Move the pointer by a relative delta. The default reads no position and can't help, so it declares itself
	 * unsupported by returning {@code false}; a device-level backend that can inject a true relative motion
	 * event (XTest) overrides it, returns {@code true}, and thereby works even under a pointer grab/warp
	 * (mouselook), where reading an absolute position to add the delta to is unreliable.
	 */
	default boolean moveRelative(int dx, int dy) {
		return false;
	}

	/** Press/release a mouse button at the current pointer position. 1=left, 2=middle, 3=right. */
	void button(int button, boolean press);

	/** Press/release a key given its X keysym (delivered to whatever currently holds focus). */
	void key(int keysym, boolean press);

	/**
	 * Press/release a key delivered to {@code window} specifically rather than the focused window — the
	 * keyboard analogue of {@link #clickWindow}. Only the cursor-preserving {@link XSendEventBackend}
	 * targets a window; the cursor-moving backends drive the one real device and have no per-window notion,
	 * so they keep the default (delegate to the focused-window {@link #key(int, boolean)} path).
	 *
	 * <p>That default is a last resort, not the policy:
	 * {@link com.botmaker.shared.capture.linux.LinuxController} checks {@link #preservesCursor()} and raises
	 * the target window itself before sending a global key, so a targeted key doesn't quietly land on
	 * whatever had focus. Don't route around it by calling this on a cursor-moving backend.
	 */
	default void key(Pointer window, int keysym, boolean press) {
		key(keysym, press);
	}

	/** Scroll: {@code +} = up/away, {@code -} = down/toward. */
	void scroll(int amount);

	/**
	 * Release every key and button this backend is currently holding down (and undo any temporary keymap
	 * changes). Called from the typing path's {@code finally} and on teardown so an interrupted sequence can't
	 * leave a modifier stuck — the classic cause of input "going insane" after a run of actions. Default:
	 * nothing, for stateless backends that never hold anything across calls.
	 */
	default void releaseHeld() {}

	/**
	 * How long, in milliseconds, the typing loop should pause between successive characters so a fast
	 * {@code typeText} doesn't outrun the target's input queue. Default {@code 0} (no pacing); a device-level
	 * backend returns its configured inter-key delay.
	 */
	default int interKeyDelayMs() {
		return 0;
	}

	/**
	 * Tell this backend which window the caller believes it is driving, as a supplier read afresh on each use
	 * (a session re-attaches, so a captured handle goes stale — see {@code NestedSession.attached()}).
	 *
	 * <p>Only a backend whose coordinates are relative to <em>something</em> can use this: on gamescope's
	 * Xwayland ({@link PointerWarp#FOCUS_RELATIVE}) the warp origin has to come from a window, and reading it
	 * from whatever currently holds focus is a guess. Every other backend ignores it, hence the no-op default.
	 */
	default void setDrivenWindow(Supplier<Pointer> window) {}

	/** Release any native resources (e.g. a uinput device). Default: nothing. */
	@Override
	default void close() {}
}
