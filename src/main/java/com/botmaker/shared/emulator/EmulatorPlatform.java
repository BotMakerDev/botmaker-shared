package com.botmaker.shared.emulator;

import java.util.List;

/**
 * One Android-emulator product (BlueStacks, LDPlayer, …). Its whole job is <em>discovery</em>: read the
 * product's local config/registry and report which instances exist and on what ADB port — the transport
 * underneath ({@link AdbDevice}, dadb) is the same for all of them, so a platform never speaks ADB itself.
 *
 * <p>Every method is best-effort and Windows-first: on a machine where the product isn't installed (or on a
 * non-Windows OS), {@link #discover()} returns an empty list rather than throwing. Adding MEmu/MuMu/Gameloop
 * is a new implementation with a config parser — nothing else in the stack changes.
 */
public interface EmulatorPlatform {

    /** Stable product key stamped onto every {@link EmulatorInstance}, e.g. {@code "bluestacks"}. */
    String id();

    /** Human-readable product name for UI/logs, e.g. {@code "BlueStacks"}. */
    String displayName();

    /** All locally-configured instances of this product, each with its ADB port. Never throws; empty if none. */
    List<EmulatorInstance> discover();
}
