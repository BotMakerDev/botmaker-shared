package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorLiveness;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.EmulatorState;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.QuietRun;
import com.botmaker.shared.emulator.WaydroidApps;
import com.botmaker.shared.emulator.WaydroidCli;
import com.botmaker.shared.emulator.WaydroidStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Starting, stopping and probing an app <em>inside</em> a named Android emulator instance — the
 * {@code emu-app:<package>@<instance>} launch kind.
 *
 * <p>Built straight on shared's own emulator layer ({@link Platforms} discovery, {@link EmulatorLauncher} host
 * control, {@link AdbDevice} transport) rather than on the SDK's {@code Emulators}/{@code EmulatorRef}
 * convenience wrappers over them, so Studio can drive it without the SDK.
 *
 * <p><b>Every step reports what happened.</b> This used to be best-effort-and-logged: {@code start} returned
 * {@code void} and wrote its failures to {@link Diag}, so a caller could only say "launched" and hope. That
 * hid a real bug for a whole round — the emulator came up, the app never started, and the UI said success —
 * and it hid the three different reasons it could happen. {@link Outcome} names them instead, and
 * {@link Launcher#start} turns a failure into the same kind of exception a missing Steam produces.
 */
public final class EmulatorAppLauncher {

    /** How long the app is given to reach the foreground after the launcher intent is accepted. */
    private static final Duration FOREGROUND_TIMEOUT = Duration.ofSeconds(20);

    /** How long between foreground checks. */
    private static final long FOREGROUND_POLL_MS = 1_000;

    private EmulatorAppLauncher() {}

    /**
     * What a launch attempt did — {@code ok} plus a sentence a UI can show verbatim.
     *
     * <p>A record rather than an enum because the message names the instance, the package and, for the ADB
     * case, the thing the user has to go and do; and because a caller should never have to keep its own
     * switch over our failure modes to write them out.
     */
    public record Outcome(boolean ok, String message) {

        static Outcome ok(String message) {
            return new Outcome(true, message);
        }

        static Outcome failed(String message) {
            return new Outcome(false, message);
        }
    }

    /**
     * Brings {@code instance} up if needed, waits for Android to actually finish booting, starts
     * {@code packageName}'s launcher activity and confirms it reached the foreground.
     *
     * @param progress optional narration for a UI that shows the wait (a cold container takes minutes);
     *                 called on the calling thread, so a UI toolkit consumer marshals it itself
     */
    public static Outcome start(String packageName, String instance, Consumer<String> progress) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isPresent() && match.get().platformId() == PlatformId.WAYDROID) {
            return startWaydroid(packageName, match.get(), progress);
        }
        return withReadyEmulator(instance, progress, (device, live) -> {
            report(progress, "Starting " + packageName + " on " + live.name() + "…");
            Diag.log("[Target] emu-app: starting " + packageName + " on " + instance);
            QuietRun.calm(device);
            return startOverAdb(AppDevice.of(device), packageName, live.name());
        });
    }

    /**
     * The little a launch asks of a device, so a test answers it without dadb: a shell command, and the foreground
     * package.
     */
    interface AppDevice {
        String shell(String command);

        String currentApp();

        static AppDevice of(AdbDevice device) {
            return new AppDevice() {
                @Override
                public String shell(String command) {
                    return device.shell(command);
                }

                @Override
                public String currentApp() {
                    return device.currentApp();
                }
            };
        }
    }

    /**
     * The Waydroid rung: start the app through Waydroid's own CLI rather than over ADB.
     *
     * <p>This is not a preference, it is the difference between working and not. {@code waydroid app launch}
     * unfreezes a container that froze itself ({@code suspend_action = freeze} is the default, and a frozen
     * container still answers on its ADB port), sets {@code waydroid.active_apps} so the app becomes the
     * rendered surface, and starts the session when one isn't running — so a cold start is this one command
     * and needs no separate emulator bring-up. See {@link WaydroidApps} for the whole comparison.
     *
     * <p>Verification asks <em>Waydroid</em> first ({@code waydroid.active_apps}), because that needs no ADB
     * and so survives the in-guest trust prompt; ADB is the second opinion and the fallback rung. A launch we
     * cannot confirm through either is reported as dispatched, not as failed.
     */
    private static Outcome startWaydroid(String packageName, EmulatorInstance instance, Consumer<String> progress) {
        Optional<String> missing = notInstalled(packageName);
        if (missing.isPresent()) {
            return Outcome.failed(missing.get());
        }
        boolean wasRunning = WaydroidStatus.read().sessionRunning();
        report(progress, "Starting " + packageName + " in Waydroid…");
        Diag.log("[Target] emu-app: waydroid app launch " + packageName);
        if (!WaydroidApps.launch(packageName)) {
            return Outcome.failed("Couldn't run Waydroid's own launcher for " + packageName
                    + ". Check that the 'waydroid' command works from a terminal.");
        }
        report(progress, "Waydroid is bringing " + packageName + " up — waiting for it to appear…");
        Duration budget = wasRunning ? FOREGROUND_TIMEOUT : instance.platformId().bootTimeout();
        if (awaitActiveApp(packageName, budget)) {
            // Waydroid's own answer, and the one that needs no ADB — so it still works when the in-guest
            // trust prompt has blocked every query. If it says the app is the active surface, it is.
            calm(instance);
            return Outcome.ok("Started " + packageName + " in Waydroid.");
        }
        Optional<EmulatorInstance> ready = EmulatorReadiness.awaitReady(instance, instance.platformId().bootTimeout());
        if (ready.isEmpty()) {
            // The launch was dispatched and Waydroid owns the rest; we simply have no channel to confirm it.
            return Outcome.ok("Asked Waydroid to start " + packageName + ". It didn't answer on ADB within "
                    + instance.platformId().bootTimeout().toSeconds() + "s, so its state can't be confirmed "
                    + "from here — check the Waydroid window.");
        }
        EmulatorInstance live = ready.get();
        try (AdbDevice device = connect(live)) {
            QuietRun.calm(device);
            if (reachedForeground(AppDevice.of(device), packageName)) {
                return Outcome.ok("Started " + packageName + " in Waydroid.");
            }
            // Dispatched, but something else is still in front. Fall through to the ADB rung: it can start
            // the activity directly, and if that fails too it can say what Android thinks of the package.
            Diag.log("[Target] emu-app: waydroid launch didn't surface " + packageName + " — trying ADB");
            return startOverAdb(AppDevice.of(device), packageName, live.name());
        } catch (Exception e) {
            return Outcome.ok("Asked Waydroid to start " + packageName + " — couldn't confirm over ADB ("
                    + e.getMessage() + "). Check the Waydroid window.");
        }
    }

    /**
     * The ADB rung, used as-is by every console-tool emulator and as Waydroid's fallback: {@code monkey}
     * first, then the explicitly resolved component.
     *
     * <p>The second try exists because monkey matches a launcher intent and some apps don't publish one the
     * way it expects — {@code cmd package resolve-activity} asks the package manager the same question
     * directly, and {@code am start -n} then names the component instead of hoping.
     *
     * <p>Before either, the package manager's list says whether the app is there at all: an app missing from an
     * instance is the commonest reason nothing starts, and the user's remedy is an install, not a package name.
     */
    static Outcome startOverAdb(AppDevice device, String packageName, String instance) {
        String pkg = packageName.trim();
        if (Boolean.FALSE.equals(installed(device.shell("pm list packages"), pkg))) {
            Diag.log("[Target] emu-app: " + pkg + " isn't installed on " + instance);
            return Outcome.failed(pkg + " isn't installed on " + instance + ". Install it from the game's "
                    + "Install… button, then launch again.");
        }
        if (AdbDevice.startedApp(device.shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"))) {
            return awaitForeground(device, pkg, instance);
        }
        String component = resolveLauncherComponent(device, pkg);
        if (component != null) {
            Diag.log("[Target] emu-app: monkey declined, starting " + component + " directly");
            device.shell(AdbDevice.startComponent(component));
            return awaitForeground(device, pkg, instance);
        }
        return Outcome.failed("Android refused to start " + pkg + " on " + instance
                + " — no launcher activity answered, and the package manager couldn't resolve one either. "
                + "Check the package name.");
    }

    /**
     * Whether {@code pm list packages} lists {@code pkg}, or {@code null} when the output lists nothing at all (no
     * answer is not evidence of absence).
     */
    static Boolean installed(String pmList, String pkg) {
        if (pmList == null || !pmList.contains("package:")) return null;
        return pmList.lines().map(String::strip).anyMatch(line -> line.equals("package:" + pkg));
    }

    /** {@code <pkg>/<activity>} for the app's launcher activity, or {@code null} when nothing resolves. */
    private static String resolveLauncherComponent(AppDevice device, String packageName) {
        try {
            return AdbDevice.launcherComponent(device.shell(AdbDevice.RESOLVE_LAUNCHER + packageName.trim()),
                    packageName).orElse(null);
        } catch (Exception e) {
            Diag.log("[Target] emu-app: resolve-activity failed for " + packageName + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * A sentence naming {@code packageName} as absent, when the host CLI can list the container's apps and it
     * isn't among them — the one check that can fail fast, before spending a boot timeout on an app that was
     * never installed. Empty when the list is unavailable (no answer is not evidence of absence).
     */
    private static Optional<String> notInstalled(String packageName) {
        List<WaydroidApps.InstalledApp> apps = WaydroidApps.list();
        if (apps.isEmpty()) {
            return Optional.empty();
        }
        boolean present = apps.stream().anyMatch(app -> app.packageName().equals(packageName.trim()));
        return present ? Optional.empty()
                : Optional.of(packageName + " isn't installed in Waydroid — 'waydroid app list' doesn't "
                        + "mention it. Re-pick the app in the emulator picker.");
    }

    /** {@link #start(String, String, Consumer)} with no progress narration. */
    public static Outcome start(String packageName, String instance) {
        return start(packageName, instance, null);
    }

    /**
     * Force-stops {@code packageName} and starts it again — a clean restart inside a live instance.
     *
     * <p>The stop is always ADB ({@code am force-stop}, which Waydroid's CLI has no equivalent for); the
     * start then goes through {@link #start}, so it takes the same rung a fresh launch would.
     *
     * <p><b>The emulator is restarted too when it is the broken part.</b> A restart is what a run's recovery asks
     * for when the game can't be got home, and on a desktop emulator that is often the emulator: its process up
     * but ADB gone or Android no longer booted. An instance that is up but not ready within {@link #HUNG_GRACE} is
     * stopped first, so the start boots it again; an emulator that died outright is started by the start
     * itself. Either way the restart is the one the run's {@code RestartCap} counted.
     */
    public static Outcome restart(String packageName, String instance) {
        Optional<EmulatorInstance> desktop = find(instance).filter(match -> match.platformId() != PlatformId.WAYDROID);
        desktop.ifPresent(EmulatorAppLauncher::rebootIfHung);
        if (desktop.isEmpty()) {
            stop(packageName, instance);
        } else if (forceStop(packageName, instance, RESTART_LEFTOVER_WAIT) == Leftover.STAYED) {
            stopEmulator(desktop.get(), "a process of " + packageName.trim() + " outlived the force-stop");
        }
        return start(packageName, instance, null);
    }

    /** How long a reboot's stop may take. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Stops {@code instance} when it is up but not ready within its product's boot timeout, so a start boots it
     * again. The whole boot timeout, so an emulator still on a slow cold boot is never taken for a hung one.
     */
    private static void rebootIfHung(EmulatorInstance instance) {
        if (EmulatorReadiness.isReady(instance)) return;
        if (EmulatorLiveness.check(instance).state() == EmulatorState.STOPPED) return;
        Duration grace = instance.platformId().bootTimeout();
        Diag.log("[Target] emu-app: " + instance.name() + " is up but not answering — waiting up to "
                + grace.toSeconds() + "s before restarting it");
        long end = System.nanoTime() + grace.toNanos();
        try {
            while (System.nanoTime() < end) {
                Thread.sleep(2000);
                if (EmulatorReadiness.isReady(instance)) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        stopEmulator(instance, "ADB or Android stopped answering");
    }

    /** Stops the emulator under a broken game or a hung Android, saying why in the run's log. */
    private static boolean stopEmulator(EmulatorInstance instance, String why) {
        if (!instance.canStop()) {
            Diag.log("[Target] emu-app: " + why + " on " + instance.name() + ", and BotMaker can't stop "
                    + instance.brand() + ": restart it by hand");
            return false;
        }
        Diag.log("[Target] emu-app: " + why + " on " + instance.name() + " — restarting the emulator");
        try {
            boolean stopped = EmulatorLauncher.stopAndWait(instance, STOP_TIMEOUT);
            if (!stopped) Diag.log("[Target] emu-app: " + instance.name() + " did not stop within "
                    + STOP_TIMEOUT.toSeconds() + "s");
            return stopped;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** How long a force-stopped app's processes may take to go before what is left counts as a leftover. */
    private static final Duration LEFTOVER_WAIT = Duration.ofSeconds(3);

    /**
     * As {@link #LEFTOVER_WAIT}, before a restart stops a desktop emulator for it: a game still saving as it exits
     * must not cost the whole emulator.
     */
    private static final Duration RESTART_LEFTOVER_WAIT = Duration.ofSeconds(15);

    /** What a force-stop left. */
    private enum Leftover { NONE, STAYED, NOT_SENT }

    /**
     * Force-stops {@code packageName} and starts nothing: the stop half of {@link #restart}. A stop that cannot be
     * sent is logged, never thrown: starting an app already running is a no-op.
     *
     * <p>A process of the app that outlives the force-stop is a leftover: a game's own forked helper, which Android
     * no longer tracks, cannot end and will not start the app again beside ("refused to die", Sword x Staff on
     * Waydroid, 2026-11-03); only root could kill it. On Waydroid the session is stopped then, which ends every
     * process in the container, so the next start boots Android again. A desktop emulator is stopped for it only by
     * {@link #restart}, which starts it again; a plain stop of the app never takes the emulator and its other apps.
     *
     * @return whether the emulator itself was stopped to end the app, so whatever showed it is gone too
     */
    public static boolean stop(String packageName, String instance) {
        if (forceStop(packageName, instance, LEFTOVER_WAIT) != Leftover.STAYED) return false;
        if (find(instance).map(match -> match.platformId() != PlatformId.WAYDROID).orElse(true)) {
            Diag.log("[Target] emu-app: a process of " + packageName.trim() + " outlived the force-stop on " + instance);
            return false;
        }
        Diag.log("[Target] emu-app: a process of " + packageName.trim() + " outlived the force-stop and Android won't "
                + "start it again beside it — stopping the Waydroid session");
        WaydroidCli.waydroid("session", "stop");
        return true;
    }

    /** {@code am force-stop}, then whether a process of the app is still there after {@code wait}. */
    private static Leftover forceStop(String packageName, String instance, Duration wait) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isEmpty() || !EmulatorLiveness.running(match.get())) return Leftover.NOT_SENT;
        String pkg = packageName.trim();
        try (AdbDevice device = connect(match.get())) {
            Diag.log("[Target] emu-app: stopping " + pkg + " on " + instance);
            device.shell("am force-stop " + pkg);
            return outlives(device, pkg, wait) ? Leftover.STAYED : Leftover.NONE;
        } catch (Exception e) {
            Diag.log("[Target] emu-app: force-stop failed on " + instance + ": " + e.getMessage());
            return Leftover.NOT_SENT;
        }
    }

    /** Whether a process of {@code pkg} is still there {@code wait} after its force-stop. */
    private static boolean outlives(AdbDevice device, String pkg, Duration wait) throws InterruptedException {
        long until = System.nanoTime() + wait.toNanos();
        while (true) {
            String pids = device.shell("pidof " + pkg);
            if (pids == null || pids.isBlank()) return false;
            if (System.nanoTime() > until) return true;
            Thread.sleep(250);
        }
    }

    /**
     * Whether {@code packageName} is the foreground app on {@code instance} right now, asked over ADB — the
     * same channel this kind's capture path uses. Nothing on the host process table describes an app running
     * inside an emulator, so the generic {@link RunningProbe} layers cannot answer this one.
     */
    public static boolean isRunning(String packageName, String instance) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isEmpty() || !EmulatorLiveness.running(match.get())) {
            return false;
        }
        try (AdbDevice device = connect(match.get())) {
            String current = device.currentApp();
            return current != null && current.contains(packageName);
        } catch (Exception e) {
            Diag.log("[Target] emu-app: probing " + instance + " failed: " + e.getMessage());
            return false;
        }
    }

    /** One thing done with a live device, given the instance as discovery currently describes it. */
    @FunctionalInterface
    private interface DeviceAction {
        Outcome run(AdbDevice device, EmulatorInstance live) throws Exception;
    }

    /**
     * Resolves the named instance, ensures it is <em>ready</em> (launch + wait for {@code sys.boot_completed},
     * not merely for the ADB port), connects, and runs {@code action}.
     *
     * <p>The readiness distinction is the fix this class was rewritten for. Polling the port answered "yes"
     * while Android was still booting, so the launcher intent went to a system that couldn't resolve it, and
     * the failure looked exactly like success.
     */
    private static Outcome withReadyEmulator(String instance, Consumer<String> progress, DeviceAction action) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isEmpty()) {
            Diag.log("[Target] emu-app: no emulator instance named '" + instance + "'");
            return Outcome.failed("No emulator instance named '" + instance + "' was found. Open the emulator "
                    + "picker to see what this machine has, and re-pick the launch target.");
        }
        Optional<EmulatorInstance> ready = EmulatorReadiness.bringUp(match.get(), progress);
        if (ready.isEmpty()) {
            Diag.log("[Target] emu-app: instance '" + instance + "' did not become ready");
            return Outcome.failed(EmulatorReadiness.notReady(match.get()));
        }
        EmulatorInstance live = ready.get();
        try (AdbDevice device = connect(live)) {
            return action.run(device, live);
        } catch (Exception e) {
            Diag.log("[Target] emu-app: " + instance + " failed: " + e.getMessage());
            return Outcome.failed("Couldn't talk to " + instance + " over ADB: " + e.getMessage());
        }
    }

    /**
     * Waits briefly for {@code packageName} to become the foreground app, so "started" means started.
     *
     * <p>An accepted launcher intent is not the same as a running app: a game can die on its splash screen,
     * and monkey has already returned success by then. Not reaching the foreground is reported as a partial
     * outcome rather than a failure — the intent really was accepted, and a slow game is not an error.
     */
    private static Outcome awaitForeground(AppDevice device, String packageName, String instance) {
        return reachedForeground(device, packageName)
                ? Outcome.ok("Started " + packageName + " on " + instance + ".")
                : Outcome.ok("Launched " + packageName + " on " + instance + " — it hasn't reached the "
                        + "foreground yet, which is normal for a game that is still loading.");
    }

    /**
     * Waits for Waydroid to report {@code packageName} as its active app.
     *
     * <p>The budget depends on what we asked for: a cold start is the whole container coming up, a warm one
     * should answer within a poll or two. Giving the warm case the cold budget would mean four minutes of
     * waiting before the ADB fallback rung ever ran.
     */
    private static boolean awaitActiveApp(String packageName, Duration budget) {
        long deadline = System.currentTimeMillis() + budget.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (packageName.trim().equals(WaydroidApps.activeApp())) {
                return true;
            }
            try {
                Thread.sleep(FOREGROUND_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Whether {@code packageName} became the foreground app within {@link #FOREGROUND_TIMEOUT}. Separate from
     * the {@link Outcome} above because the Waydroid rung needs the bare fact: a "no" there is not a report to
     * the user, it is the cue to try the next rung.
     */
    private static boolean reachedForeground(AppDevice device, String packageName) {
        long deadline = System.currentTimeMillis() + FOREGROUND_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String current = device.currentApp();
            if (current != null && current.contains(packageName)) {
                return true;
            }
            try {
                Thread.sleep(FOREGROUND_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** The discovered instance the target names, if any — {@link EmulatorInstances#find}'s one owner. */
    private static Optional<EmulatorInstance> find(String reference) {
        return EmulatorInstances.find(reference);
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) {
            progress.accept(message);
        }
    }

    private static AdbDevice connect(EmulatorInstance instance) {
        return AdbDevice.connect(instance.adb());
    }

    /** {@link QuietRun#calm} when ADB answers; a Waydroid whose trust prompt blocks it is left as it is. */
    private static void calm(EmulatorInstance instance) {
        try (AdbDevice device = connect(instance)) {
            QuietRun.calm(device);
        } catch (Exception e) {
            Diag.log("[Target] emu-app: popups left on, ADB did not answer: " + e.getMessage());
        }
    }
}
