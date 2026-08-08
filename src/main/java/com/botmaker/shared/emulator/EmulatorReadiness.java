package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * <b>Can this instance be driven yet?</b> — the single answer, for every consumer that starts an emulator and
 * then wants to do something with it.
 *
 * <p>It exists because that question had two different answers and both were wrong. The launch path
 * ({@code EmulatorAppLauncher}) and Studio's picker each carried their own TCP probe of the ADB port and
 * treated a connection as "it's up". On a desktop emulator that is roughly true; on a container it is off by
 * minutes. {@code adbd} starts listening when the container's network comes up, while the package manager
 * that has to resolve a launcher intent is still booting — so an app launch fired into a half-booted Android
 * and quietly did nothing, and a picker that queried {@code pm list packages} at the same moment came back
 * with an empty app list. {@link #isReady} asks Android itself ({@code sys.boot_completed}) instead.
 *
 * <p><b>Waiting re-resolves the instance.</b> {@link #awaitReady} re-runs discovery on each pass rather than
 * polling the snapshot it was handed. An {@link EmulatorInstance} carries an address, and for Waydroid that
 * address is a property of a <em>running</em> container — {@code WaydroidPlatform.discover} reads it from
 * {@code waydroid status} and falls back to {@link WaydroidStatus#DEFAULT_IP} while the session is down. The
 * instance you have before a launch is therefore the one you knew about before it had told you anything.
 *
 * <p>Everything here is best-effort and total: an unreachable port, a refused ADB handshake or a missing
 * property all read as "not ready", never as an exception.
 */
public final class EmulatorReadiness {

    /** How long between passes while waiting for an instance to become ready. */
    private static final long POLL_MS = 2_000;

    private EmulatorReadiness() {}

    /**
     * Whether something is answering at the instance's address. Cheap enough to run for every row of a
     * picker, and the right question for a running/stopped dot — but <em>not</em> for "can I drive it",
     * which is {@link #isReady}.
     *
     * <p>Delegates to {@link AdbEndpoint#reachable()}, because the answer differs by variant: a TCP connect
     * is meaningless for a device the adb server owns by serial. This method used to <em>be</em> that socket
     * connect, one of the two copies that motivated moving the probe onto the endpoint.
     */
    public static boolean portOpen(EmulatorInstance instance) {
        return instance != null && instance.reachable();
    }

    /**
     * Whether the instance is booted far enough to accept work: the port answers <em>and</em> Android reports
     * {@code sys.boot_completed}. A refused ADB handshake — the "Allow USB debugging?" prompt still waiting
     * inside the guest — also answers false here, because from a caller's point of view it is the same
     * situation: the device is not driveable yet.
     */
    public static boolean isReady(EmulatorInstance instance) {
        return portOpen(instance) && bootCompleted(instance);
    }

    /** {@link AdbDevice#bootCompleted()} over a short-lived connection; false when we can't ask. */
    private static boolean bootCompleted(EmulatorInstance instance) {
        try (AdbDevice device = AdbDevice.connect(instance.adb())) {
            return device.bootCompleted();
        } catch (Throwable t) {
            // dadb surfaces some failures as Errors; a readiness probe never propagates either kind.
            return false;
        }
    }

    /**
     * Polls until {@code instance} is ready or {@code timeout} elapses, returning the <em>freshly discovered</em>
     * instance (whose address is the one it actually came up on), or empty on timeout.
     *
     * <p>Interruption ends the wait immediately and reports "not ready" rather than swallowing the flag — a
     * closed dialog must not leave a thread counting out four minutes.
     */
    public static Optional<EmulatorInstance> awaitReady(EmulatorInstance instance, Duration timeout) {
        return awaitReady(instance, timeout, EmulatorReadiness::rediscover, EmulatorReadiness::isReady);
    }

    /**
     * {@link #awaitReady(EmulatorInstance, Duration)} against injected discovery and readiness probes — the
     * testable seam, so the re-resolve and the timeout are asserted without a socket or an emulator.
     */
    static Optional<EmulatorInstance> awaitReady(EmulatorInstance instance, Duration timeout,
                                                 UnaryOperator<EmulatorInstance> rediscover,
                                                 Predicate<EmulatorInstance> ready) {
        if (instance == null) {
            return Optional.empty();
        }
        long deadline = System.currentTimeMillis() + Math.max(0, timeout.toMillis());
        EmulatorInstance current = instance;
        while (true) {
            current = rediscover.apply(current);
            if (ready.test(current)) {
                return Optional.of(current);
            }
            if (System.currentTimeMillis() >= deadline) {
                return Optional.empty();
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * The instance as discovery sees it <em>now</em>, matched by {@link EmulatorInstance#identity()} first and
     * then by name — identity encodes the address, so it is the stricter match and the one that fails exactly
     * when the address has moved, which is the case the name match then catches. Falls back to the instance
     * passed in when discovery finds nothing (a product whose config disappeared mid-wait).
     */
    static EmulatorInstance rediscover(EmulatorInstance instance) {
        try {
            List<EmulatorInstance> found = Platforms.discoverAll();
            for (EmulatorInstance candidate : found) {
                if (candidate.identity().equals(instance.identity())) {
                    return candidate;
                }
            }
            for (EmulatorInstance candidate : found) {
                if (candidate.platformId() == instance.platformId() && candidate.name().equals(instance.name())) {
                    Diag.log("[Emulator] " + instance.name() + " moved to " + candidate.endpoint());
                    return candidate;
                }
            }
        } catch (Exception e) {
            Diag.log("[Emulator] re-discovery failed while waiting for " + instance.name() + ": " + e.getMessage());
        }
        return instance;
    }
}
