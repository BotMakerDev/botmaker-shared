package com.botmaker.shared.input;

/**
 * A single real (human) input event observed by an {@link InputListener}. Coordinates are <b>absolute
 * screen</b> (root-relative) pixels; {@code timestampMs} is wall-clock ({@link System#currentTimeMillis()})
 * at observation, used by consumers to measure idle gaps and detect double-clicks.
 *
 * <p>Lives in {@code botmaker-shared} because the native listener produces these and the Studio consumes
 * them — one definition of the vocabulary, mirroring {@link com.botmaker.shared.ipc.TelemetryEvent}. This is
 * the <em>observe</em> counterpart to the interface's input-<em>synthesis</em> methods; nothing here injects
 * input.
 */
public sealed interface InputEvent
		permits InputEvent.ButtonPress, InputEvent.ButtonRelease, InputEvent.Motion,
		        InputEvent.KeyPress, InputEvent.KeyRelease {

	/** Wall-clock time the event was observed, in milliseconds. */
	long timestampMs();

	/** A pointer button went down. {@code button}: 1=left, 2=middle, 3=right, 4/5=wheel up/down. */
	record ButtonPress(int button, int x, int y, long timestampMs) implements InputEvent {}

	/** A pointer button was released. */
	record ButtonRelease(int button, int x, int y, long timestampMs) implements InputEvent {}

	/** Pointer motion to an absolute position (only meaningful between a press and release, for drag). */
	record Motion(int x, int y, long timestampMs) implements InputEvent {}

	/**
	 * A key went down. {@code keysym} is the X keysym resolved for the current shift level (so a printable
	 * key already carries its cased code point, e.g. {@code 'A'} vs {@code 'a'}); {@code keycode} is the raw
	 * physical code for reference. Consumers map the keysym to typed text or a key name.
	 */
	record KeyPress(int keycode, long keysym, long timestampMs) implements InputEvent {}

	/** A key was released. */
	record KeyRelease(int keycode, long keysym, long timestampMs) implements InputEvent {}
}
