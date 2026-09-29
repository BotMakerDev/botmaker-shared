package com.botmaker.shared;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one stack walk: the first frame a caller's skip test does not refuse, never the walk's own, and a lambda
 * named after the method it was written in.
 */
class CallersTest {

    /** Plumbing a caller skips, standing in for {@code Diag} or the SDK's {@code Debug}. */
    private static final class Plumbing {
        static Optional<StackWalker.StackFrame> caller() {
            return Diag.Callers.first(f -> f.getDeclaringClass() == Plumbing.class);
        }
    }

    @Test
    void theFirstFrameBeyondTheSkippedOnesIsTheCaller() {
        StackWalker.StackFrame frame = Plumbing.caller().orElseThrow();

        assertEquals(CallersTest.class, frame.getDeclaringClass());
        assertEquals("theFirstFrameBeyondTheSkippedOnesIsTheCaller", frame.getMethodName());
        assertTrue(frame.getLineNumber() > 0);
    }

    @Test
    void withNothingSkippedTheCallerIsWhoeverCalledTheWalk() {
        StackWalker.StackFrame frame = Diag.Callers.first(f -> false).orElseThrow();

        assertEquals(CallersTest.class, frame.getDeclaringClass());
    }

    @Test
    void whenEveryFrameIsSkippedThereIsNoCaller() {
        assertTrue(Diag.Callers.first(f -> true).isEmpty());
    }

    @Test
    void aLambdaIsNamedAfterTheMethodItWasWrittenIn() {
        Supplier<StackWalker.StackFrame> inside = () -> Plumbing.caller().orElseThrow();
        String name = inside.get().getMethodName();

        assertTrue(name.startsWith("lambda$"), name);
        assertEquals("aLambdaIsNamedAfterTheMethodItWasWrittenIn", Diag.Callers.method(name));
        assertEquals("click", Diag.Callers.method("click"));
        assertEquals("lambda$", Diag.Callers.method("lambda$"));
    }
}
