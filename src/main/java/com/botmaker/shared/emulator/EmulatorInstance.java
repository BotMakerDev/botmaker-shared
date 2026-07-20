package com.botmaker.shared.emulator;

import java.util.List;

/**
 * A single, discovered emulator instance: which product it belongs to, its user-facing name, the
 * {@code host:port} where its {@code adbd} listens, and (when the product ships a console tool) the host
 * commands that start and stop it. Discovery ({@link EmulatorPlatform#discover()}) produces these; a consumer
 * turns one into a live {@link AdbDevice} via {@link AdbDevice#connect(String, int)}, or launches/stops it
 * through {@link EmulatorLauncher}.
 *
 * @param platformId    which product this instance belongs to
 * @param name          the instance name shown in the emulator's multi-instance manager
 * @param host          ADB host — always loopback for a local emulator ({@code "127.0.0.1"})
 * @param adbPort       the instance's ADB port
 * @param launchCommand the host process + args that start this instance (empty if the product has no console
 *                      tool we can drive, or it couldn't be located)
 * @param stopCommand   the host process + args that stop this instance (empty when unsupported)
 */
public record EmulatorInstance(PlatformId platformId, String name, String host, int adbPort,
                               List<String> launchCommand, List<String> stopCommand) {

    public EmulatorInstance {
        platformId = platformId == null ? PlatformId.UNKNOWN : platformId;
        launchCommand = launchCommand == null ? List.of() : List.copyOf(launchCommand);
        stopCommand = stopCommand == null ? List.of() : List.copyOf(stopCommand);
    }

    /** A discovery-only descriptor with no launch/stop support (the parsers' pure form). */
    public EmulatorInstance(PlatformId platformId, String name, String host, int adbPort) {
        this(platformId, name, host, adbPort, List.of(), List.of());
    }

    /** {@code host:port} label, for logging / identity / an ADB connect. */
    public String endpoint() {
        return host + ":" + adbPort;
    }

    /**
     * A stable key that is unique per instance — {@code platformId@host:adbPort}. Use this to de-duplicate or
     * cache instances rather than the display name, which several instances routinely share (it defaults to
     * the same string in most multi-instance managers) and which would let one product's instance be mistaken
     * for another's.
     */
    public String identity() {
        return platformId.id() + "@" + endpoint();
    }

    /** The product's human-readable name, e.g. {@code "BlueStacks"}. */
    public String brand() {
        return platformId.displayName();
    }

    /** A copy of this instance carrying the given host launch/stop commands. */
    public EmulatorInstance withCommands(List<String> launch, List<String> stop) {
        return new EmulatorInstance(platformId, name, host, adbPort, launch, stop);
    }

    /** Whether {@link EmulatorLauncher#launch} can start this instance (a launch command is known). */
    public boolean canLaunch() {
        return !launchCommand.isEmpty();
    }

    /** Whether {@link EmulatorLauncher#stop} can stop this instance (a stop command is known). */
    public boolean canStop() {
        return !stopCommand.isEmpty();
    }
}
