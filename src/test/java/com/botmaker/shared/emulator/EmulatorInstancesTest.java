package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
        assertTrue(EmulatorInstances.find(null).isEmpty());
        assertTrue(EmulatorInstances.find("").isEmpty());
        assertTrue(EmulatorInstances.find("   ").isEmpty());
    }

    @Test
    void aNameNoProductCouldEverReportIsEmpty() {
        assertTrue(EmulatorInstances.find("no-such-instance-4f2b9c").isEmpty());
    }

    /**
     * A phone is saved by its serial, which the adb server reports before USB debugging is allowed as well as
     * after — its model name only after (2026-10-19). A bot that saved the name still finds it.
     */
    @Test
    void aPhoneIsFoundBySerialAndStillByItsOldName() {
        EmulatorInstance phone = new EmulatorInstance(PlatformId.PHYSICAL, "Pixel 7", new AdbEndpoint.Server("R58M12"));
        // An emulator named as the phone's model is not taken for it: the serial is matched first.
        EmulatorInstance namesake = new EmulatorInstance(PlatformId.BLUESTACKS, "Pixel 7", AdbEndpoint.loopback(5555));
        List<EmulatorInstance> all = List.of(namesake, phone);
        assertEquals("R58M12", phone.reference());
        assertEquals(Optional.of(phone), EmulatorInstances.find(all, " R58M12 "));
        assertEquals(Optional.of(namesake), EmulatorInstances.find(all, "Pixel 7"));
        assertEquals(Optional.of(phone), EmulatorInstances.find(List.of(phone), "Pixel 7"));
    }

    /** An emulator keeps its name as its reference: its port is what moves. */
    @Test
    void anEmulatorIsSavedByItsName() {
        assertEquals("Nougat64", new EmulatorInstance(
                PlatformId.BLUESTACKS, "Nougat64", AdbEndpoint.loopback(5565)).reference());
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
