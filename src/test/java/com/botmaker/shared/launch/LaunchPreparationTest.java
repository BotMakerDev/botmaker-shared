package com.botmaker.shared.launch;

import com.botmaker.shared.emulator.WaydroidResolution;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a launch changes about the host before it runs — and, mostly, what it doesn't.
 *
 * <p>Only the branches that <b>touch nothing</b> are asserted here, on purpose: the Waydroid branch sets
 * persistent Android properties and cycles the container, so a test that exercised it would restart whatever
 * the machine running it happens to have open. That path is verified live against a real container instead;
 * what is worth pinning automatically is that every other target returns before reaching it, since a
 * preparation step that runs for the wrong target is exactly a restart nobody asked for.
 */
class LaunchPreparationTest {

    @Test
    void nothingIsPreparedForATargetThatIsNotAnEmulatorApp() {
        assertFalse(LaunchPreparation.prepare(LaunchSpec.parse("steam:570"), 1080, 1920));
        assertFalse(LaunchPreparation.prepare(LaunchSpec.parse("exe:/opt/game/run.sh"), 1080, 1920));
        assertFalse(LaunchPreparation.prepare(null, 1080, 1920));
    }

    /** Every other Android product boots on its own; there is no property of ours for it to read. */
    @Test
    void nothingIsPreparedForAnotherAndroidProduct() {
        assertFalse(LaunchPreparation.prepare(LaunchSpec.parse("emu-app:com.foo@Pie64"), 1080, 1920));
        assertFalse(LaunchPreparation.prepare(LaunchSpec.parse("emu-app:com.foo@MuMu Player"), 1080, 1920));
        assertFalse(LaunchPreparation.prepare(new LaunchSpec(LaunchKind.EMULATOR_APP, "com.foo"), 1080, 1920));
    }

    /**
     * An unstated size prepares nothing rather than guessing — and returns before probing for Waydroid at all,
     * which is what keeps this assertion free of a {@code waydroid} spawn.
     */
    @Test
    void anUnstatedSizePreparesNothing() {
        assertFalse(WaydroidResolution.useForNextStart(0, 1920));
        assertFalse(WaydroidResolution.useForNextStart(1080, -1));
    }
}
