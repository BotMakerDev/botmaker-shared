package com.botmaker.shared.capture.linux.input;

/**
 * The narrow native surface {@link Keymap} needs of an X server's keyboard mapping — factored out as an
 * interface so the spare-keycode rebind logic can be unit-tested against an in-memory table instead of a live
 * display. {@link XlibKeymapOps} is the real implementation over {@code libX11}; tests supply a fake.
 *
 * <p>Keycodes are the server's physical codes (always within {@link #minKeycode()}..{@link #maxKeycode()},
 * itself a subset of 8..255). Each keycode maps to {@link #keysymsPerKeycode()} keysyms, one per shift level
 * (index 0 unshifted, 1 shifted, …); an unbound level is {@code 0} (NoSymbol).
 */
interface KeymapOps {

	/** Lowest physical keycode the server uses. */
	int minKeycode();

	/** Highest physical keycode the server uses. */
	int maxKeycode();

	/** How many keysyms (shift levels) each keycode carries — the row width of the mapping table. */
	int keysymsPerKeycode();

	/** The keysyms currently bound to {@code keycode}, one per shift level (length {@link #keysymsPerKeycode()}). */
	long[] keysymsFor(int keycode);

	/** Install {@code keysyms} (length {@link #keysymsPerKeycode()}) as {@code keycode}'s new mapping. */
	void rebind(int keycode, long[] keysyms);

	/** Round-trip to the server so a preceding {@link #rebind} has taken effect before the key is injected. */
	void sync();
}
