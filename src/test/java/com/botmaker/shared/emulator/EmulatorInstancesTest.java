package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * A name alone cannot say what product it belongs to, so the caption must not claim one — and in
     * particular must not say "Emulator", which is what every one of these sites hard-coded back when an
     * emulator was the only Android surface this stack could reach.
     */
    @Test
    void aNameOnlyCaptionDoesNotCallAPhoneAnEmulator() {
        assertEquals("Android: Pixel 7", EmulatorInstances.captionFor("Pixel 7"));
        assertEquals("Android: (any)", EmulatorInstances.captionFor(""));
        assertEquals("Android: (any)", EmulatorInstances.captionFor(null));
    }

    /** A resolved instance does know its product, so its caption names it. */
    @Test
    void aResolvedInstanceCaptionsWithItsBrand() {
        assertEquals("Android device: Pixel 7", new EmulatorInstance(
                PlatformId.PHYSICAL, "Pixel 7", new AdbEndpoint.Tcp("192.168.1.5", 5555)).caption());
        assertEquals("BlueStacks: Nougat64", new EmulatorInstance(
                PlatformId.BLUESTACKS, "Nougat64", AdbEndpoint.loopback(5555)).caption());
    }
}
