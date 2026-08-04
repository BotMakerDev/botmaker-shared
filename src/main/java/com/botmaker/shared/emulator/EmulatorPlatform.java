package com.botmaker.shared.emulator;

import java.util.List;

/**
 * One Android-emulator product (BlueStacks, LDPlayer, …). Its whole job is <em>discovery</em>: read the
 * product's local config/registry and report which instances exist and on what ADB port — the transport
 * underneath ({@link AdbDevice}, dadb) is the same for all of them, so a platform never speaks ADB itself.
 *
 * <p>Every method is best-effort: on a machine where the product isn't installed, {@link #discover()} returns
 * an empty list rather than throwing. Adding a product is a new implementation with a config parser — nothing
 * else in the stack changes.
 *
 * <p><b>Not Windows-only.</b> This javadoc used to promise "empty on non-Windows", which was true only
 * because every implementation began with a {@link WindowsRegistry} read behind a hard OS gate.
 * {@link WaydroidPlatform} broke that: it is Linux-only, gates on the {@code waydroid} binary being on
 * {@code PATH}, and discovers a container rather than a configured instance. So the honest contract is
 * per-implementation — <em>each</em> platform decides which OS it can appear on, and says so on its own class.
 */
public interface EmulatorPlatform {

    /** Which product this is — stamped onto every {@link EmulatorInstance} it discovers. */
    PlatformId id();

    /** Human-readable product name for UI/logs, e.g. {@code "BlueStacks"}. Comes from {@link #id()}. */
    default String displayName() {
        return id().displayName();
    }

    /**
     * Whether this product appears installed on the machine (registry/install-dir present), independent of how
     * many instances are configured or running. Lets a picker distinguish "installed but no instance" from
     * "not installed at all". Best-effort; {@code false} on an OS this product doesn't run on, or when
     * detection fails.
     */
    boolean isInstalled();

    /** All locally-configured instances of this product, each with its ADB port. Never throws; empty if none. */
    List<EmulatorInstance> discover();
}
