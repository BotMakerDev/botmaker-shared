package com.botmaker.shared.launch;

import com.botmaker.shared.Executables;
import com.botmaker.shared.emulator.WaydroidApps;
import com.botmaker.shared.emulator.WaydroidPlatform;
import com.botmaker.shared.emulator.WaydroidResolution;
import com.botmaker.shared.emulator.WaydroidStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The <em>child-launchable</em> argv ladders for a {@link LaunchSpec} — the command lines that run a target
 * as our own child process, so it inherits whatever environment we hand it (notably a private {@code DISPLAY}
 * for a nested session). This is the single source both launch paths draw from, so they can't drift on how a
 * store launcher is spelled:
 *
 * <ul>
 *   <li>the on-host {@code :0} path ({@link GameLauncher}, which tries a protocol URL first and these as its
 *       CLI fallback), and</li>
 *   <li>the nested-display path ({@code NestedSession.commandFor}), which cannot use a daemon-routed URL at
 *       all — the URL would be handed to a launcher already running on {@code :0}, ignoring our
 *       {@code DISPLAY}. It runs these directly.</li>
 * </ul>
 *
 * <p>Each store kind yields an <em>ordered ladder</em> of candidate argvs, most-preferred first (a native
 * binary, then its Flatpak form); a caller runs them in order until one works. A kind with no CLI form at all
 * (Epic offers none) yields an <b>empty</b> ladder, which the nested session reads as "can't be launched into
 * a private display".
 *
 * <p><b>An emulator app is the one kind whose answer depends on the product.</b> Waydroid's UI is a Wayland
 * client we start ourselves, so it has a real child command and can be nested (see {@link #emulatorApp});
 * every other Android product is reached over ADB after something else started it, and still yields nothing.
 */
public final class LaunchCommands {

    private LaunchCommands() {}

    /**
     * Heroic's CLI ladder: the native binary, then the common Flatpak install.
     *
     * <p><b>The launch request is the protocol URL, passed as an argument.</b> Heroic has no {@code launch}
     * subcommand — its whole CLI surface is {@code --no-gui} plus a {@code heroic://} URL it reads out of
     * {@code process.argv} (its own Steam-shortcut generator writes exactly
     * {@code --no-gui --no-sandbox "heroic://launch?appName=…&runner=…"}). The earlier spelling
     * {@code --no-gui launch <id>} was silently ignored: Heroic booted its full frontend with the window hidden,
     * launched nothing, and a nested session then timed out waiting for a window that was never coming.
     *
     * <p>This is <em>not</em> the same as handing the URL to {@code xdg-open} ({@link GameLauncher#heroic}'s
     * first rung): the opener routes it to whatever Heroic is already on {@code :0}, whereas here Heroic is our
     * own child and inherits our {@code DISPLAY}. {@code --no-sandbox} matches Heroic's own non-Windows argv.
     */
    public static List<List<String>> heroic(String appName) {
        String uri = "heroic://launch/" + require(appName);
        return ladder(LaunchKind.HEROIC, "heroic", List.of("--no-gui", "--no-sandbox", uri));
    }

    /** Steam's CLI ladder: {@code steam -applaunch <appId>}, then the Flatpak install's form. */
    public static List<List<String>> steam(String appId) {
        return ladder(LaunchKind.STEAM, "steam", List.of("-applaunch", require(appId)));
    }

    /** Faugus's CLI ladder: the native launcher, then its Flatpak form. */
    public static List<List<String>> faugus(String gameId) {
        return ladder(LaunchKind.FAUGUS, "faugus-launcher", List.of("--game", require(gameId)));
    }

    /**
     * The two-rung ladder every store kind has: the native {@code binary} with {@code args}, then the same
     * arguments behind {@code flatpak run <appId>}. The app id comes from {@link LaunchKind#flatpakAppId()} —
     * the one copy, in the canonical case {@code flatpak run} is sensitive to.
     */
    private static List<List<String>> ladder(LaunchKind kind, String binary, List<String> args) {
        List<String> native0 = new ArrayList<>(args.size() + 1);
        native0.add(binary);
        native0.addAll(args);
        List<String> flatpak = new ArrayList<>(args.size() + 3);
        flatpak.add("flatpak");
        flatpak.add("run");
        flatpak.add(kind.flatpakAppId());
        flatpak.addAll(args);
        return List.of(List.copyOf(native0), List.copyOf(flatpak));
    }

    /**
     * The full child-launch ladder for {@code spec}: the store ladders above for the launcher kinds, a single
     * argv for {@code exe:}/{@code cli:} (they already <em>are</em> the child process), and an empty ladder for
     * any kind with no way to inherit a child environment — Epic's URL-only handoff, an emulator app over ADB,
     * or an unknown/blank spec. Empty means "not launchable into a private display".
     */
    public static List<List<String>> childLadder(LaunchSpec spec) {
        return childLadder(spec, 0, 0);
    }

    /**
     * The same ladder, told the size of the display it will run on — which only an emulator app uses, and
     * which it cannot do without.
     *
     * <p>Its rung <em>is</em> a compositor ({@code gamescope … waydroid app launch}), so the size in that argv
     * decides what Android renders at. Sizing it from the container's own properties instead put a
     * {@code -W 1920 -H 1080} gamescope on a 1080×1920 display — measured — which is the letterboxing the
     * private display exists to remove. The display is the authority here because it was built from the
     * project's reference resolution; the container is made to agree with it separately.
     *
     * <p>A non-positive size means "not stated", and falls back to the container's properties.
     */
    public static List<List<String>> childLadder(LaunchSpec spec, int width, int height) {
        if (spec == null) {
            return List.of();
        }
        if (spec.kind() == LaunchKind.EMULATOR_APP) {
            return emulatorApp(spec, width, height);
        }
        return switch (spec.kind()) {
            case EXE -> spec.token().isBlank() ? List.of() : List.of(List.of(spec.token()));
            case CLI -> {
                String[] tokens = spec.commandTokens();
                yield tokens.length == 0 ? List.of() : List.of(List.of(tokens));
            }
            case HEROIC -> heroic(spec.token());
            case STEAM -> steam(spec.token());
            case FAUGUS -> faugus(spec.token());
            case EMULATOR_APP, EPIC, UNKNOWN -> List.of();
        };
    }

    /**
     * The nested-display ladder for an emulator app: <b>Waydroid only</b>, and only when gamescope is installed.
     *
     * <p>Waydroid is the one Android product that can run inside a private display, because its UI is a
     * Wayland client we start ourselves — {@code gamescope --backend sdl … waydroid app launch <pkg>} is a
     * single child command that inherits our {@code DISPLAY}. Every other product is a host application that
     * was already running before the bot asked for it and is reached over ADB; there is nothing to hand a
     * display to, so their ladder stays empty and {@code LaunchIsolation} refuses them with the same message as
     * before.
     *
     * <p><b>The size comes from the container, not from here.</b> gamescope is sized to
     * {@link WaydroidResolution#read}, which is what Android will actually boot at — the two must agree or the
     * grab is letterboxed. Making them agree with the <em>project's</em> resolution is the launcher's job (it
     * applies the props first); by the time this argv is built the container is the authority.
     */
    private static List<List<String>> emulatorApp(LaunchSpec spec, int width, int height) {
        if (!WaydroidPlatform.INSTANCE_NAME.equalsIgnoreCase(trimmed(spec.emulatorInstance()))) {
            // Decided by name rather than by discovery on purpose: there is one Waydroid container per machine
            // and it always answers to this name, so the question is settled without spawning `waydroid status`
            // — which this would otherwise do on every launch of every other product.
            return List.of();
        }
        return emulatorApp(spec.emulatorPackage(), nestedSize(width, height),
                Executables.onPath(Executables.GAMESCOPE));
    }

    /**
     * The size the nested gamescope is given: the display's when it is stated, else whatever the container is
     * configured for. Separate and package-private because <em>which of the two wins</em> is the fix, and it
     * is worth asserting without a {@code PATH} that has gamescope on it.
     */
    static WaydroidResolution nestedSize(int width, int height) {
        return width > 0 && height > 0
                ? new WaydroidResolution(width, height)
                : WaydroidResolution.read(WaydroidStatus.read().sessionRunning());
    }

    /** {@link #emulatorApp(LaunchSpec)} for a known-Waydroid spec, with every probe injected — the pure form. */
    static List<List<String>> emulatorApp(String packageName, WaydroidResolution resolution,
                                          boolean gamescopeOnPath) {
        List<String> command = WaydroidApps.nestedLaunchCommand(packageName, resolution, gamescopeOnPath);
        return command.isEmpty() ? List.of() : List.of(command);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("launch token must not be empty");
        }
        return value.trim();
    }
}
