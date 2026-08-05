package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.Platforms;

import java.time.Duration;
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
        return withReadyEmulator(instance, progress, (device, live) -> {
            report(progress, "Starting " + packageName + " on " + live.name() + "…");
            Diag.log("[Target] emu-app: starting " + packageName + " on " + instance);
            if (!AdbDevice.startedApp(device.startApp(packageName))) {
                return Outcome.failed("Android refused to start " + packageName + " on " + live.name()
                        + " — no launcher activity answered. Check the package name, and that the app is "
                        + "installed on this instance.");
            }
            return awaitForeground(device, packageName, live);
        });
    }

    /** {@link #start(String, String, Consumer)} with no progress narration. */
    public static Outcome start(String packageName, String instance) {
        return start(packageName, instance, null);
    }

    /** Force-stops {@code packageName} and starts it again — a clean restart inside a live instance. */
    public static Outcome restart(String packageName, String instance) {
        return withReadyEmulator(instance, null, (device, live) -> {
            Diag.log("[Target] emu-app: restarting " + packageName + " on " + instance);
            device.shell("am force-stop " + packageName.trim());
            if (!AdbDevice.startedApp(device.startApp(packageName))) {
                return Outcome.failed("Android refused to restart " + packageName + " on " + live.name()
                        + " — no launcher activity answered.");
            }
            return awaitForeground(device, packageName, live);
        });
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
        long deadline = System.currentTimeMillis() + FOREGROUND_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String current = device.currentApp();
            if (current != null && current.contains(packageName)) {
                return Outcome.ok("Started " + packageName + " on " + live.name() + ".");
            }
            try {
                Thread.sleep(FOREGROUND_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Outcome.ok("Launched " + packageName + " on " + live.name() + " — it hasn't reached the "
                + "foreground yet, which is normal for a game that is still loading.");
    }

    /** The discovered instance with this name, if any. Names are what the multi-instance manager shows. */
    private static Optional<EmulatorInstance> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Platforms.discoverAll().stream().filter(i -> name.equals(i.name())).findFirst();
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
