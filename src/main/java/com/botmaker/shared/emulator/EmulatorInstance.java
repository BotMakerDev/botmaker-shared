package com.botmaker.shared.emulator;

import java.util.List;

/**
 * A single, discovered Android surface: which product it belongs to, its user-facing name, {@link #adb()} —
 * where its {@code adbd} can be reached — and (when the product ships a console tool) the host commands that
 * start and stop it. Discovery ({@link EmulatorPlatform#discover()}) produces these; a consumer turns one into
 * a live {@link AdbDevice} via {@link AdbDevice#connect(AdbEndpoint)}, or launches/stops it through
 * {@link EmulatorLauncher}.
 *
 * <p><b>The address is an {@link AdbEndpoint}, not a host and a port.</b> A physical phone on a USB cable has
 * no TCP address at all — see that type for why a pair of fields could only write it down as {@code ""} and
 * {@code 0}, and why every consumer's probe would then call a working phone "not running".
 *
 * @param platformId    which product this instance belongs to
 * @param name          the instance name shown in the emulator's multi-instance manager, or a phone's model
 * @param adb           where this instance's {@code adbd} is
 * @param launchCommand the host process + args that start this instance (empty if the product has no console
 *                      tool we can drive, it couldn't be located, or — for a phone — there is nothing to start)
 * @param stopCommand   the host process + args that stop this instance (empty when unsupported)
 */
public record EmulatorInstance(PlatformId platformId, String name, AdbEndpoint adb,
                               List<String> launchCommand, List<String> stopCommand) {

    public EmulatorInstance {
        platformId = platformId == null ? PlatformId.UNKNOWN : platformId;
        launchCommand = launchCommand == null ? List.of() : List.copyOf(launchCommand);
        stopCommand = stopCommand == null ? List.of() : List.copyOf(stopCommand);
    }

    /** A discovery-only descriptor with no launch/stop support (the parsers' pure form). */
    public EmulatorInstance(PlatformId platformId, String name, AdbEndpoint adb) {
        this(platformId, name, adb, List.of(), List.of());
    }

    /** The overwhelmingly common shape: an {@code adbd} on a TCP host and port. */
    public EmulatorInstance(PlatformId platformId, String name, String host, int adbPort) {
        this(platformId, name, new AdbEndpoint.Tcp(host, adbPort), List.of(), List.of());
    }

    /** As above, with the product's console commands. */
    public EmulatorInstance(PlatformId platformId, String name, String host, int adbPort,
                            List<String> launchCommand, List<String> stopCommand) {
        this(platformId, name, new AdbEndpoint.Tcp(host, adbPort), launchCommand, stopCommand);
    }

    /** The address as a stable, loggable, persistable string — {@code host:port}, or a device serial. */
    public String endpoint() {
        return adb.label();
    }

    /** Whether something is answering at {@link #adb()} right now. See {@link AdbEndpoint#reachable()}. */
    public boolean reachable() {
        return adb.reachable();
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

    /**
     * The one-line caption a picker or a target chip shows — {@code "BlueStacks: Nougat64"}, or
     * {@code "Android device: Pixel 7"} for a phone.
     *
     * <p>It is formed here rather than at each call site because those call sites had all hard-coded the word
     * <em>Emulator</em>, which was true of everything this stack could reach until a phone could be one.
     * A resolved instance always knows its own product; see {@link EmulatorInstances#captionFor(String)} for
     * what a saved reference that knows only a name can honestly say instead.
     */
    public String caption() {
        return brand() + ": " + (name == null || name.isBlank() ? "(any)" : name);
    }

    /** A copy of this instance carrying the given host launch/stop commands. */
    public EmulatorInstance withCommands(List<String> launch, List<String> stop) {
        return new EmulatorInstance(platformId, name, adb, launch, stop);
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
