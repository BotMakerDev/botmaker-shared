package com.botmaker.shared.capture.linux.input;

/**
 * The small, deliberate pauses a device-level backend inserts around a click or keystroke so that the target
 * <em>observes</em> the event rather than dropping it. It is the codified answer to the "the click landed but
 * the game ignored it" class of flakiness: toolkits and games sample the pointer and update hover state on
 * their own frame timer, so a press issued in the same instant as the motion is read at the <em>old</em>
 * position, and a press/release with no gap between them can be coalesced away.
 *
 * <p>The sequence a hardened click follows is: move → {@code XSync} (flush + round-trip so the server has
 * actually applied the motion) → {@link #motionSettleMs()} → press → {@link #pressHoldMs()} → release. Typing
 * inserts {@link #interKeyMs()} between characters so a fast {@code typeText} doesn't outrun a game's input
 * queue. Immutable; tune with the {@code with*} methods (a real target may need longer holds).
 */
public final class InputTiming {

	/** Conservative defaults that survive most toolkits/games without being sluggish (all in milliseconds). */
	public static final InputTiming DEFAULT = new InputTiming(12, 12, 8);

	private final int motionSettleMs;
	private final int pressHoldMs;
	private final int interKeyMs;

	private InputTiming(int motionSettleMs, int pressHoldMs, int interKeyMs) {
		this.motionSettleMs = Math.max(0, motionSettleMs);
		this.pressHoldMs = Math.max(0, pressHoldMs);
		this.interKeyMs = Math.max(0, interKeyMs);
	}

	/** Pause after the pointer has moved (and the move has round-tripped) before pressing. */
	public int motionSettleMs() {
		return motionSettleMs;
	}

	/** Pause a button/key is held down between press and release. */
	public int pressHoldMs() {
		return pressHoldMs;
	}

	/** Pause between successive characters while typing a string. */
	public int interKeyMs() {
		return interKeyMs;
	}

	public InputTiming withMotionSettle(int ms) {
		return new InputTiming(ms, pressHoldMs, interKeyMs);
	}

	public InputTiming withPressHold(int ms) {
		return new InputTiming(motionSettleMs, ms, interKeyMs);
	}

	public InputTiming withInterKey(int ms) {
		return new InputTiming(motionSettleMs, pressHoldMs, ms);
	}
}
