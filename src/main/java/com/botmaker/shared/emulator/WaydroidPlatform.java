package com.botmaker.shared.emulator;

import com.botmaker.shared.Executables;
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
 * <p><b>gamescope is never launched unsized.</b> When {@link WaydroidResolution} reports a framebuffer size,
 * gamescope is sized to match it, so the Android surface fills the window 1:1 and matched coordinates mean what
 * they say. When it reports nothing, the flags carry {@link WaydroidResolution#DEFAULT} rather than being
 * omitted: omitting them lets gamescope pick its own output size, which is a scaler by any other name, and it
 * is the path that actually ran here — {@code prop get} cannot answer with the container down, which is the
 * only moment this argv is built. See {@link WaydroidResolution#DEFAULT} for why a chosen size is safe where a
 * guessed one would not be.
 */
public final class WaydroidPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.WAYDROID;

    /**
     * There is one container, so there is one name; it is what a bot writes in {@code Emulators.use(…)}.
     *
     * <p>Public because it is also the <em>identity</em> of the product in a launch target
     * ({@code emu-app:<pkg>@Waydroid}): {@code LaunchCommands} decides whether a target can run on a private
     * display by comparing against this, which settles the question without spawning a discovery probe.
     */
    public static final String INSTANCE_NAME = "Waydroid";

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
                launchCommand(WaydroidResolution.read(status.sessionRunning()),
                        WaydroidCli.onPath(Executables.GAMESCOPE)),
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
        return gamescoped(waydroidCommand, resolution, gamescopeOnPath, false);
    }

    /**
     * {@link #gamescoped(List, WaydroidResolution, boolean)}, but stating whether this gamescope is being
     * started <em>inside a private display</em> rather than on the user's desktop.
     *
     * <p>The nested form adds {@code --backend sdl}, which is what makes gamescope open an ordinary X window on
     * the {@code DISPLAY} it inherits instead of choosing a backend from the ambient session. Unsized and
     * unmanaged there, that window fills the nested screen exactly — so the Android surface, gamescope's
     * internal resolution and the display are all one number and the capture is 1:1.
     *
     * <p><b>Why this is the only way to see Waydroid's pixels at full resolution.</b> Waydroid is a
     * Wayland-only client: unlike an X11 game it maps no window a session could capture, so its pixels exist
     * only in gamescope's composited output. On the desktop that output is a window the window manager resizes
     * at will — measured here at 1280×661 for a 1080×1920 container, letterboxing the image into roughly a
     * third of its linear resolution. In a private display there is no window manager to resize it.
     */
    static List<String> gamescoped(List<String> waydroidCommand, WaydroidResolution resolution,
                                   boolean gamescopeOnPath, boolean nested) {
        if (!gamescopeOnPath) {
            return List.copyOf(waydroidCommand);
        }
        // Unknown is not a reason to omit the flags — an unsized gamescope picks its own output and scales
        // Android into it, which is exactly the failure the sizing exists to prevent.
        WaydroidResolution size = resolution != null ? resolution : WaydroidResolution.DEFAULT;
        String w = Integer.toString(size.width());
        String h = Integer.toString(size.height());
        List<String> command = new ArrayList<>();
        command.add(Executables.GAMESCOPE);
        if (nested) {
            command.addAll(List.of("--backend", "sdl"));
        }
        // -W/-H is the output window, -w/-h the internal resolution clients see. Equal on purpose: any
        // difference between them is a scaler, and a scaler between the bot's templates and the pixels it
        // clicks on is the bug this whole class is arranged to avoid (see GamescopeDisplay.defaultCommand,
        // which keeps them equal for the same reason).
        command.addAll(List.of("-W", w, "-H", h, "-w", w, "-h", h));
        command.add("--expose-wayland");   // without it gamescope hosts only its Xwayland, and Waydroid is Wayland-only
        command.addAll(waydroidCommand);
        return List.copyOf(command);
    }
}
