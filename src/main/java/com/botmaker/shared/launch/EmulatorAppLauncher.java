package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.WaydroidApps;
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
            return startOverAdb(device, packageName, live);
        });
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
            if (reachedForeground(device, packageName)) {
                return Outcome.ok("Started " + packageName + " in Waydroid.");
            }
            // Dispatched, but something else is still in front. Fall through to the ADB rung: it can start
            // the activity directly, and if that fails too it can say what Android thinks of the package.
            Diag.log("[Target] emu-app: waydroid launch didn't surface " + packageName + " — trying ADB");
            return startOverAdb(device, packageName, live);
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
     */
    private static Outcome startOverAdb(AdbDevice device, String packageName, EmulatorInstance live) {
        if (AdbDevice.startedApp(device.startApp(packageName))) {
            return awaitForeground(device, packageName, live);
        }
        String component = resolveLauncherComponent(device, packageName);
        if (component != null) {
            Diag.log("[Target] emu-app: monkey declined, starting " + component + " directly");
            device.shell("am start -n " + component);
            return awaitForeground(device, packageName, live);
        }
        return Outcome.failed("Android refused to start " + packageName + " on " + live.name()
                + " — no launcher activity answered, and the package manager couldn't resolve one either. "
                + "Check the package name, and that the app is installed on this instance.");
    }

    /**
     * {@code <pkg>/<activity>} for the app's launcher activity, or {@code null} when nothing resolves.
     * {@code cmd package resolve-activity --brief} prints the component on its last line.
     */
    private static String resolveLauncherComponent(AdbDevice device, String packageName) {
        try {
            String output = device.shell("cmd package resolve-activity --brief " + packageName.trim());
            if (output == null) {
                return null;
            }
            for (String raw : output.split("\\R")) {
                String line = raw.strip();
                if (line.startsWith(packageName.trim() + "/")) {
                    return line;
                }
            }
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
     */
    public static Outcome restart(String packageName, String instance) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isPresent() && EmulatorReadiness.portOpen(match.get())) {
            try (AdbDevice device = connect(match.get())) {
                Diag.log("[Target] emu-app: restarting " + packageName + " on " + instance);
                device.shell("am force-stop " + packageName.trim());
            } catch (Exception e) {
                // A stop we couldn't send isn't fatal: starting an app that is already running is a no-op.
                Diag.log("[Target] emu-app: force-stop failed on " + instance + ": " + e.getMessage());
            }
        }
        return start(packageName, instance, null);
    }

    /**
     * Whether {@code packageName} is the foreground app on {@code instance} right now, asked over ADB — the
     * same channel this kind's capture path uses. Nothing on the host process table describes an app running
     * inside an emulator, so the generic {@link RunningProbe} layers cannot answer this one.
     */
    public static boolean isRunning(String packageName, String instance) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isEmpty() || !EmulatorReadiness.portOpen(match.get())) {
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
        Optional<EmulatorInstance> ready = awaitReady(match.get(), instance, progress);
        if (ready.isEmpty()) {
            Duration budget = match.get().platformId().bootTimeout();
            Diag.log("[Target] emu-app: instance '" + instance + "' did not become ready");
            return Outcome.failed(instance + " didn't finish booting within " + budget.toSeconds() + "s. It may "
                    + "still be starting — try again in a moment — or Android is up but ADB isn't answering, "
                    + "which is usually an \"Allow USB debugging?\" prompt waiting inside the emulator.");
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
     * The instance once it is ready, launching it (once) first when it isn't up. The returned instance is the
     * re-discovered one, so a container that came up on a different address is talked to at that address.
     */
    private static Optional<EmulatorInstance> awaitReady(EmulatorInstance instance, String name,
                                                         Consumer<String> progress) {
        if (EmulatorReadiness.isReady(instance)) {
            return Optional.of(instance);
        }
        if (!EmulatorReadiness.portOpen(instance)) {
            Diag.log("[Target] emu-app: launching emulator instance '" + name + "'");
            report(progress, "Starting " + name + "…");
            EmulatorLauncher.launch(instance);
        }
        report(progress, name + " is booting — waiting for Android to finish starting…");
        return EmulatorReadiness.awaitReady(instance, instance.platformId().bootTimeout());
    }

    /**
     * Waits briefly for {@code packageName} to become the foreground app, so "started" means started.
     *
     * <p>An accepted launcher intent is not the same as a running app: a game can die on its splash screen,
     * and monkey has already returned success by then. Not reaching the foreground is reported as a partial
     * outcome rather than a failure — the intent really was accepted, and a slow game is not an error.
     */
    private static Outcome awaitForeground(AdbDevice device, String packageName, EmulatorInstance live) {
        return reachedForeground(device, packageName)
                ? Outcome.ok("Started " + packageName + " on " + live.name() + ".")
                : Outcome.ok("Launched " + packageName + " on " + live.name() + " — it hasn't reached the "
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
    private static boolean reachedForeground(AdbDevice device, String packageName) {
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

    /** The discovered instance with this name, if any — {@link EmulatorInstances#byName}'s one owner. */
    private static Optional<EmulatorInstance> find(String name) {
        return EmulatorInstances.byName(name);
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) {
            progress.accept(message);
        }
    }

    private static AdbDevice connect(EmulatorInstance instance) {
        return AdbDevice.connect(instance.host(), instance.adbPort());
    }
}
