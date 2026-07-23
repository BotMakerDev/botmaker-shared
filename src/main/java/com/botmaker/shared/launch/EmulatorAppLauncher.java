package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.Platforms;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Starting, stopping and probing an app <em>inside</em> a named Android emulator instance — the
 * {@code emu-app:<package>@<instance>} launch kind.
 *
 * <p>Built straight on shared's own emulator layer ({@link Platforms} discovery, {@link EmulatorLauncher} host
 * control, {@link AdbDevice} transport) rather than on the SDK's {@code Emulators}/{@code EmulatorRef}
 * convenience wrappers over them, so Studio can drive it without the SDK. Every step is best-effort and
 * logged: a missing instance or an emulator that never boots is a no-op the caller can retry, not an exception
 * that ends a bot run.
 */
public final class EmulatorAppLauncher {

    /** How long to wait for a just-launched emulator instance to come up before giving up. */
    private static final long BOOT_TIMEOUT_MS = 120_000;

    /** How long between liveness polls while an instance boots. */
    private static final long POLL_MS = 2_000;

    /** TCP connect timeout for the ADB liveness probe — cheaper than a full ADB handshake. */
    private static final int PROBE_TIMEOUT_MS = 300;

    private EmulatorAppLauncher() {}

    /** Brings {@code instance} up if needed and starts {@code packageName}'s launcher activity. */
    public static void start(String packageName, String instance) {
        withRunningEmulator(instance, device -> {
            Diag.log("[Target] emu-app: starting " + packageName + " on " + instance);
            device.startApp(packageName);
        });
    }

    /** Force-stops {@code packageName} and starts it again — a clean restart inside a live instance. */
    public static void restart(String packageName, String instance) {
        withRunningEmulator(instance, device -> {
            Diag.log("[Target] emu-app: restarting " + packageName + " on " + instance);
            device.shell("am force-stop " + packageName.trim());
            device.startApp(packageName);
        });
    }

    /**
     * Whether {@code packageName} is the foreground app on {@code instance} right now, asked over ADB — the
     * same channel this kind's capture path uses. Nothing on the host process table describes an app running
     * inside an emulator, so the generic {@link RunningProbe} layers cannot answer this one.
     */
    public static boolean isRunning(String packageName, String instance) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isEmpty() || !reachable(match.get())) {
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

    /**
     * Resolves the named instance, ensures it is running (launch + wait), connects, hands the live device to
     * {@code action}, then disconnects. No-op (logged) when the instance can't be found or brought up.
     */
    private static void withRunningEmulator(String instance, Consumer<AdbDevice> action) {
        Optional<EmulatorInstance> match = find(instance);
        if (match.isEmpty()) {
            Diag.log("[Target] emu-app: no emulator instance named '" + instance + "'");
            return;
        }
        if (!awaitRunning(match.get(), instance)) {
            Diag.log("[Target] emu-app: instance '" + instance + "' did not come up");
            return;
        }
        try (AdbDevice device = connect(match.get())) {
            action.accept(device);
        } catch (Exception e) {
            Diag.log("[Target] emu-app: " + instance + " failed: " + e.getMessage());
        }
    }

    /** True once the instance answers on ADB, launching it (once) and polling up to {@link #BOOT_TIMEOUT_MS}. */
    private static boolean awaitRunning(EmulatorInstance instance, String name) {
        if (reachable(instance)) {
            return true;
        }
        Diag.log("[Target] emu-app: launching emulator instance '" + name + "'");
        EmulatorLauncher.launch(instance);
        long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (reachable(instance)) {
                return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                // Someone is shutting us down; stop waiting and report "not up" rather than swallowing it.
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return reachable(instance);
    }

    /** The discovered instance with this name, if any. Names are what the multi-instance manager shows. */
    private static Optional<EmulatorInstance> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Platforms.discoverAll().stream().filter(i -> name.equals(i.name())).findFirst();
    }

    /** A quick TCP probe of the ADB port: something listening means the instance is (very likely) up. */
    private static boolean reachable(EmulatorInstance instance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(instance.host(), instance.adbPort()), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static AdbDevice connect(EmulatorInstance instance) {
        return AdbDevice.connect(instance.host(), instance.adbPort());
    }
}
