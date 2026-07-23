package com.botmaker.shared.capture.linux.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit coverage for {@link InputTiming}: sane defaults, independent immutable {@code with*} updates, no negatives. */
class InputTimingTest {

	@Test
	void defaultsArePositiveAndDistinctKnobs() {
		InputTiming t = InputTiming.DEFAULT;
		assertEquals(12, t.motionSettleMs());
		assertEquals(12, t.pressHoldMs());
		assertEquals(8, t.interKeyMs());
	}

	@Test
	void withMethodsChangeOnlyTheirKnobAndDontMutateTheBase() {
		InputTiming base = InputTiming.DEFAULT;
		InputTiming tuned = base.withMotionSettle(30).withPressHold(25).withInterKey(5);

		assertEquals(30, tuned.motionSettleMs());
		assertEquals(25, tuned.pressHoldMs());
		assertEquals(5, tuned.interKeyMs());
		// The base is untouched.
		assertEquals(12, base.motionSettleMs());
		assertEquals(12, base.pressHoldMs());
		assertEquals(8, base.interKeyMs());
	}

	@Test
	void negativeDelaysAreClampedToZero() {
		InputTiming t = InputTiming.DEFAULT.withMotionSettle(-5).withPressHold(-1).withInterKey(-100);
		assertEquals(0, t.motionSettleMs());
		assertEquals(0, t.pressHoldMs());
		assertEquals(0, t.interKeyMs());
	}
}
