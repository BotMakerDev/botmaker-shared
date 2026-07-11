package com.botmaker.shared.input;

import java.util.function.Consumer;

/**
 * Observes real (human) mouse/keyboard input globally and streams {@link InputEvent}s to a sink. Passive:
 * it never intercepts or blocks input, so events still reach the focused application normally. Obtained via
 * {@link InputListenerFactory}. Backed by X11 XRecord on Linux; unsupported elsewhere (see
 * {@link InputListenerFactory#isSupported()}).
 *
 * <p>Lifecycle: {@link #start(Consumer)} spins up a background thread that delivers events (off any UI
 * thread — the consumer must marshal to its own thread as needed); {@link #close()} stops it. Not reusable
 * after close. Idempotent {@code close()}.
 */
public interface InputListener extends AutoCloseable {

	/** Begins delivering events to {@code sink} on a background thread. Call once. */
	void start(Consumer<InputEvent> sink);

	@Override
	void close();
}
