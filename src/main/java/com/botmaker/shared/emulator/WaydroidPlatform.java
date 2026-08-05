package com.botmaker.shared.emulator;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers <b>Waydroid</b> — a Linux-native Android container rather than a Windows emulator, and therefore
 * the implementation that breaks every assumption the others share.
 *
 * <ul>
 *   <li><b>No registry, no install directory.</b> Waydroid is a distro package; the install probe is
 *       {@code waydroid} being an executable on {@code PATH} ({@link WaydroidCli#available()}).</li>
 *   <li><b>No multi-instance config.</b> There is exactly one container per machine, so discovery returns one
 *       instance or none — nothing to parse, nothing to enumerate.</li>
 *   <li><b>Not on loopback.</b> Every other platform's {@code adbd} is a port forward on {@code 127.0.0.1};
 *       Waydroid's is the container's own address on the LXC bridge (default
 *       {@value WaydroidStatus#DEFAULT_IP}), port {@value WaydroidStatus#ADB_PORT}. The real address is in
 *       {@code waydroid status} once the session is up, so discovery prefers what it reports and falls back to
 *       the default while it is down.</li>
 * </ul>
 *
 * <p><b>Launching needs a Wayland compositor.</b> {@code waydroid show-full-ui} is a Wayland-only client, so
 * on the common X11 desktop it cannot start at all. gamescope — already this repo's hardware session backend —
 * is a Wayland compositor, and {@code gamescope --expose-wayland waydroid show-full-ui} is the single command
 * that was verified working on a live X11/KDE box. That is the launch argv built by {@link #launchCommand}:
 * gamescope is the parent process, not something Waydroid is attached to afterwards.
 *
 * <p>When {@link WaydroidResolution} reports a configured framebuffer size, gamescope is sized to match it, so
 * the Android surface fills the window 1:1 and matched coordinates mean what they say. When it is unset,
 * the sizing flags are <em>omitted</em> rather than guessed — a wrong guess is worse than gamescope's default,
 * because it silently introduces the scale factor this whole arrangement exists to avoid.
 */
public final class WaydroidPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.WAYDROID;

    /** The compositor that hosts the Wayland-only Waydroid UI on an X11 desktop. */
    static final String GAMESCOPE = "gamescope";

    /** There is one container, so there is one name; it is what a bot writes in {@code Emulators.use(…)}. */
    static final String INSTANCE_NAME = "Waydroid";

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return WaydroidCli.available();
    }

    @Override
    public List<EmulatorInstance> discover() {
        if (!isInstalled()) {
            return List.of();
        }
        WaydroidStatus status = WaydroidStatus.read();
        return List.of(new EmulatorInstance(PLATFORM_ID, INSTANCE_NAME, status.ipAddress(),
                WaydroidStatus.ADB_PORT,
                launchCommand(WaydroidResolution.read(), WaydroidCli.onPath(GAMESCOPE)),
                List.of(WaydroidCli.WAYDROID, "session", "stop")));
    }

    /**
     * The host command that brings the Waydroid UI up.
     *
     * <p>With gamescope present this is the verified one-liner {@code gamescope [-W …] --expose-wayland
     * waydroid show-full-ui}. Without it, the bare {@code waydroid show-full-ui} — which works on a genuine
     * Wayland desktop and fails visibly on X11, rather than being silently unavailable.
     *
     * <p>Package-private and pure (both inputs passed in) so the argv is unit-testable on any machine.
     *
     * @param resolution     the configured framebuffer size, or {@code null} when unset
     * @param gamescopeOnPath whether the compositor is available to host the Wayland client
     */
    static List<String> launchCommand(WaydroidResolution resolution, boolean gamescopeOnPath) {
        return gamescoped(List.of(WaydroidCli.WAYDROID, "show-full-ui"), resolution, gamescopeOnPath);
    }

    /**
     * {@code waydroidCommand} hosted inside a gamescope sized to the container's framebuffer, or unchanged
     * when gamescope isn't installed.
     *
     * <p>Shared with {@link WaydroidApps#launchCommand}, which needs the identical wrapping for
     * {@code waydroid app launch}: both are Wayland-only clients that have to bring a session up on an X11
     * desktop, and the sizing rule below must not exist twice.
     */
    static List<String> gamescoped(List<String> waydroidCommand, WaydroidResolution resolution,
                                   boolean gamescopeOnPath) {
        if (!gamescopeOnPath) {
            return List.copyOf(waydroidCommand);
        }
        List<String> command = new ArrayList<>();
        command.add(GAMESCOPE);
        if (resolution != null) {
            String w = Integer.toString(resolution.width());
            String h = Integer.toString(resolution.height());
            // -W/-H is the output window, -w/-h the internal resolution clients see. Equal on purpose: any
            // difference between them is a scaler, and a scaler between the bot's templates and the pixels it
            // clicks on is the bug this whole class is arranged to avoid (see GamescopeDisplay.defaultCommand,
            // which keeps them equal for the same reason).
            command.addAll(List.of("-W", w, "-H", h, "-w", w, "-h", h));
        }
        command.add("--expose-wayland");   // without it gamescope hosts only its Xwayland, and Waydroid is Wayland-only
        command.addAll(waydroidCommand);
        return List.copyOf(command);
    }
}
