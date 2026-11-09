package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.WaydroidPlatform;
import com.botmaker.shared.emulator.WaydroidResolution;
import com.botmaker.shared.emulator.WaydroidStatus;

/**
 * What has to be true of the <em>host</em> before a target is launched — the side effects that
 * {@link LaunchCommands} deliberately doesn't have.
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
     * Prepares the host for launching {@code spec} for a bot whose screen is {@code width}×{@code height}.
     *
     * <p>Today that means one thing: making Waydroid's container render at the bot's screen size (see
     * {@link WaydroidResolution#useForNextStart}). The bot's size is the authority because the bot's pictures
     * were cut at it, and the container is the thing that can be made to agree.
     *
     * <p>Only a cold start is sized: a session already up is left as it is, because cycling it would end the
     * game the user (or an earlier launch) started, and a caller that only waits for the game would then wait
     * for nothing. A size it disagrees with is logged instead.
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
            // Decided by name: it settles the question without spawning `waydroid status` on every launch of
            // every other product.
            return false;
        }
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (WaydroidStatus.read().sessionRunning()) {
            WaydroidResolution current = WaydroidResolution.read(true);
            WaydroidResolution wanted = new WaydroidResolution(width, height);
            if (current != null && !current.equals(wanted)) {
                Diag.log("[Target] waydroid is running at " + current + ", not the bot's " + wanted
                        + " — left as it is; stop Waydroid and launch again to resize it");
            }
            return false;
        }
        return WaydroidResolution.useForNextStart(width, height);
    }
}
