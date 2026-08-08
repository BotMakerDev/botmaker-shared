package com.botmaker.shared.launch;

import com.botmaker.shared.emulator.WaydroidPlatform;
import com.botmaker.shared.emulator.WaydroidResolution;

/**
 * What has to be true of the <em>host</em> before a target is launched into a private display — the side
 * effects that {@link LaunchCommands} deliberately doesn't have.
 *
 * <p>Building an argv is a pure function and worth keeping that way, so the one thing a launch must change
 * about the world before it runs lives here instead: for Waydroid, the container's own framebuffer size. That
 * size is a persistent Android property read only at session start, so it cannot be passed on a command line —
 * it has to be set, and the container cycled, <em>before</em> the launch rung runs.
 *
 * <p>Every other kind prepares nothing, which is why this is one call at the top of the launch path rather
 * than a step each caller assembles.
 */
public final class LaunchPreparation {

    private LaunchPreparation() {}

    /**
     * Prepares the host for launching {@code spec} into a display of {@code width}×{@code height}.
     *
     * <p>Today that means one thing: making Waydroid's container agree with the display it is about to render
     * into (see {@link WaydroidResolution#useForNextStart}). The display is the authority because it was sized
     * from the project's reference resolution — the size the bot's templates were authored at — and the
     * container is the thing that can be made to agree.
     *
     * <p>Best-effort and quiet for every other kind. A non-positive size means "not stated" and prepares
     * nothing rather than guessing.
     *
     * @return whether anything about the host was changed
     */
    public static boolean prepare(LaunchSpec spec, int width, int height) {
        if (spec == null || spec.kind() != LaunchKind.EMULATOR_APP) {
            return false;
        }
        String instance = spec.emulatorInstance();
        if (instance == null || !instance.equalsIgnoreCase(WaydroidPlatform.INSTANCE_NAME)) {
            // Same name check as the ladder's, and for the same reason: it settles the question without
            // spawning `waydroid status` on every launch of every other product.
            return false;
        }
        return WaydroidResolution.useForNextStart(width, height);
    }
}
