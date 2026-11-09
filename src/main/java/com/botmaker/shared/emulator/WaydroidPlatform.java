package com.botmaker.shared.emulator;

import com.botmaker.shared.Executables;
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
 * <p><b>Starting needs a Wayland compositor, not a window.</b> A Waydroid session is a Wayland client, so on
 * the common X11 desktop it cannot start at all. A headless gamescope is the compositor, showing nothing
 * (2026-11-09, the user's call: the bot reads and taps Android over ADB), and {@link #launchCommand} starts the
 * session under it: gamescope is the parent process, not something Waydroid is attached to afterwards.
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
     * ({@code emu-app:<pkg>@Waydroid}): {@code LaunchPreparation} decides whether a target needs Waydroid sized
     * by comparing against this, which settles the question without spawning a discovery probe.
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
     * The host command that brings the Waydroid session up.
     *
     * <p>With gamescope present this is {@code env -u … gamescope --backend headless [-W …] --expose-wayland
     * waydroid session start}. Without it, the bare {@code waydroid session start} — which works on a genuine
     * Wayland desktop and fails visibly on X11, rather than being silently unavailable.
     *
     * <p>Package-private and pure (both inputs passed in) so the argv is unit-testable on any machine.
     *
     * @param resolution     the configured framebuffer size, or {@code null} when unset
     * @param gamescopeOnPath whether the compositor is available to host the Wayland client
     */
    static List<String> launchCommand(WaydroidResolution resolution, boolean gamescopeOnPath) {
        return WaydroidResolution.headless(List.of(WaydroidCli.WAYDROID, "session", "start"), resolution,
                gamescopeOnPath);
    }
}
