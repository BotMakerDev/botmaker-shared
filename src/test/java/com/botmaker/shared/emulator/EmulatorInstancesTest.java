package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one lookup three call sites used to each roll themselves. Only its <em>totality</em> is machine-
 * independent and therefore testable here: a real match needs a real emulator installed, which a test must
 * never depend on (an earlier readiness test failed exactly that way on a machine that does have Waydroid).
 */
class EmulatorInstancesTest {

    @Test
    void anUnsetNameIsEmptyRatherThanAThrow() {
        // These come straight from unset config keys — capture.source with no emulator, a cleared target.
        assertTrue(EmulatorInstances.byName(null).isEmpty());
        assertTrue(EmulatorInstances.byName("").isEmpty());
        assertTrue(EmulatorInstances.byName("   ").isEmpty());
    }

    @Test
    void aNameNoProductCouldEverReportIsEmpty() {
        assertTrue(EmulatorInstances.byName("no-such-instance-4f2b9c").isEmpty());
    }
}
