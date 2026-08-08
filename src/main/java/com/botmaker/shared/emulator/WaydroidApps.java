package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;

import java.util.ArrayList;
import java.util.List;

/**
 * Waydroid's own app verbs — {@code waydroid app list} and {@code waydroid app launch} — which are the
 * <b>reliable</b> way to see and start an app in a container, and are not interchangeable with the ADB path
 * every other emulator product uses.
 *
 * <p><b>Why not ADB.</b> Starting an app over ADB is {@code monkey -p <pkg> -c …LAUNCHER 1}: it asks the
 * package manager to start an activity, and nothing else. Waydroid's own launch (read from
 * {@code tools/actions/app_manager.py}) does three more things that decide whether anything is visible:
 *
 * <ul>
 *   <li>it <b>unfreezes</b> the container first — Waydroid's default {@code suspend_action = freeze} means an
 *       idle container is frozen, which still answers on its ADB port and still looks running to every probe
 *       we have, while nothing inside it acts on an intent;</li>
 *   <li>it sets <b>{@code waydroid.active_apps}</b> to the package (plus the matching {@code policy_control}),
 *       which is what decides which app the Waydroid surface actually renders — an activity started behind
 *       its back can be running and never become the surface;</li>
 *   <li>it <b>starts the session</b> when one isn't running, so a cold start needs no separate bring-up.</li>
 * </ul>
 *
 * <p>That last point is why {@link #launchCommand} is a single argv: with gamescope wrapped around it,
 * {@code gamescope --expose-wayland waydroid app launch <pkg>} starts the compositor, the session and the app
 * in one command, with the app — not the Android launcher — as the surface. It also reuses the same
 * gamescope sizing rule as {@link WaydroidPlatform}, because a scaler between the bot's templates and the
 * pixels it clicks on is the bug that arrangement exists to avoid.
 *
 * <p>Best-effort and total like the rest of this package: no Waydroid, an unparseable listing or a failed
 * spawn is an empty list / {@code false}, never an exception.
 */
public final class WaydroidApps {

    /** The category that separates a launchable app from a content provider like {@code documentsui}. */
    static final String LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER";

    private WaydroidApps() {}

    /**
     * One app as {@code waydroid app list} describes it.
     *
     * @param packageName the id a launch target stores
     * @param label       the human name Android shows ({@code "Firestone"}), or the package when it has none
     * @param launchable  whether it declares {@link #LAUNCHER_CATEGORY} — i.e. whether it can be started
     */
    public record InstalledApp(String packageName, String label, boolean launchable) {}

    /**
     * The apps installed in the container, from the host CLI rather than over ADB.
     *
     * <p>Two things this gets that {@code pm list packages -3} does not: the app's <em>display name</em>, and
     * an answer at all when ADB is refused — Waydroid ships {@code ro.adb.secure=1}, so an unanswered "Allow
     * USB debugging?" prompt inside Android makes every ADB query fail while the port stays open.
     *
     * <p><b>Only asked when a session is already running</b>, and that guard is not politeness. The command
     * reaches the container through {@code DBusSessionService()}, a <em>session-bus</em> object lookup, which
     * D-Bus <em>activates</em> — measured here: running {@code waydroid app list} against a stopped session
     * printed the list and left the session up. That is a fine side effect of asking to launch something, and
     * a startling one for merely opening a picker, so callers that only want to read get an empty list and
     * fall back to whatever they have cached.
     */
    public static List<InstalledApp> list() {
        if (!WaydroidCli.available() || !WaydroidStatus.read().sessionRunning()) {
            return List.of();
        }
        return parseList(WaydroidCli.waydroid("app", "list"));
    }

    /**
     * Parses the block format {@code waydroid app list} prints — {@code Name:}, {@code packageName:},
     * {@code categories:} followed by indented category lines, repeated per app.
     *
     * <p>Package-private and pure so the parse is testable without Waydroid. A block missing its
     * {@code packageName} is skipped rather than emitted half-formed; the error text a stopped session prints
     * simply produces no blocks.
     */
    static List<InstalledApp> parseList(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<InstalledApp> apps = new ArrayList<>();
        String name = null;
        String pkg = null;
        boolean launchable = false;
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("Name:")) {
                // A new block begins at each Name:, so flush whatever the previous one accumulated.
                addIfComplete(apps, name, pkg, launchable);
                name = value(line);
                pkg = null;
                launchable = false;
            } else if (line.startsWith("packageName:")) {
                pkg = value(line);
            } else if (line.equals(LAUNCHER_CATEGORY)) {
                launchable = true;
            }
        }
        addIfComplete(apps, name, pkg, launchable);
        return List.copyOf(apps);
    }

    private static void addIfComplete(List<InstalledApp> apps, String name, String pkg, boolean launchable) {
        if (pkg != null && !pkg.isBlank()) {
            apps.add(new InstalledApp(pkg, (name == null || name.isBlank()) ? pkg : name, launchable));
        }
    }

    private static String value(String line) {
        return line.substring(line.indexOf(':') + 1).strip();
    }

    /** The property Waydroid sets to whatever it is currently rendering — the package, or {@code Waydroid}. */
    static final String ACTIVE_APPS_PROP = "waydroid.active_apps";

    /**
     * The app Waydroid is currently showing, or {@code null} when it can't say.
     *
     * <p>The point of asking Waydroid rather than Android: this needs no ADB, so it still answers when the
     * in-guest trust prompt has blocked every ADB query. {@code "Waydroid"} is the full-UI launcher rather
     * than an app — measured: {@code waydroid app launch com.android.documentsui} sets it to that package,
     * and {@code waydroid show-full-ui} sets it back.
     */
    public static String activeApp() {
        if (!WaydroidCli.available()) {
            return null;
        }
        String value = WaydroidCli.waydroid("prop", "get", ACTIVE_APPS_PROP);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * The host argv that starts {@code packageName} in the container.
     *
     * <p>When the session is already up this is the bare {@code waydroid app launch <pkg>} — it only has to
     * talk to Waydroid's D-Bus service. When it is down, the same command <em>also</em> brings the session up,
     * and a session needs a Wayland compositor: on the common X11 desktop that is gamescope, wrapped exactly
     * as {@link WaydroidPlatform#launchCommand} wraps {@code show-full-ui}.
     *
     * @param resolution      the configured framebuffer size, or {@code null} when unset
     * @param gamescopeOnPath whether the compositor is available to host the Wayland client
     * @param sessionRunning  whether a Waydroid session is already up
     */
    static List<String> launchCommand(String packageName, WaydroidResolution resolution,
                                      boolean gamescopeOnPath, boolean sessionRunning) {
        List<String> launch = List.of(WaydroidCli.WAYDROID, "app", "launch", packageName.trim());
        return sessionRunning ? launch : WaydroidPlatform.gamescoped(launch, resolution, gamescopeOnPath);
    }

    /**
     * Starts {@code packageName}, returning whether the command was <em>dispatched</em> — not whether the app
     * is up, which only the caller's own foreground check can say.
     *
     * <p>Dispatched detached rather than run for its output: with the session down, {@code waydroid app launch}
     * starts the session in the foreground and does not return until it ends, so capturing its output would
     * either block us for the session's lifetime or kill the session on a timeout.
     */
    public static boolean launch(String packageName) {
        if (packageName == null || packageName.isBlank() || !WaydroidCli.available()) {
            return false;
        }
        boolean sessionRunning = WaydroidStatus.read().sessionRunning();
        List<String> command = launchCommand(packageName, WaydroidResolution.read(sessionRunning),
                WaydroidCli.onPath(Executables.GAMESCOPE), sessionRunning);
        try {
            Diag.log("[Emulator] waydroid: " + String.join(" ", command));
            Spawn.detached(command);
            return true;
        } catch (Exception e) {
            Diag.log("[Emulator] waydroid app launch failed to spawn: " + e.getMessage());
            return false;
        }
    }
}
