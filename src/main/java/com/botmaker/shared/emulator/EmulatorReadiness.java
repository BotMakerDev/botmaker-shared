package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
     *
     * <p>The port must also be the instance's own ({@link EmulatorLiveness}): a stopped LDPlayer whose port
     * 5555 a running BlueStacks answers is not ready, or the launch that waited for it would drive BlueStacks.
     */
    public static boolean isReady(EmulatorInstance instance) {
        return EmulatorLiveness.running(instance) && bootCompleted(instance);
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
     * The instance once it is ready, starting it (once) first when its product says it is stopped — not when its
     * port is closed: a stopped instance whose port another emulator answers still needs starting. The returned
     * instance is the re-discovered one, so a container that came up on a different address is talked to there.
     * Empty when it isn't ready within its product's {@link PlatformId#bootTimeout()}; {@link #notReady} says why.
     *
     * @param progress optional narration, called on the calling thread
     */
    public static Optional<EmulatorInstance> bringUp(EmulatorInstance instance, Consumer<String> progress) {
        if (HeldInstances.heldElsewhere(instance)) {
            Diag.log("[Emulator] " + HeldInstances.why(instance));
            return Optional.empty();
        }
        if (isReady(instance)) {
            return Optional.of(instance);
        }
        EmulatorLiveness liveness = EmulatorLiveness.check(instance);
        if (liveness.clash() != null) Diag.log("[Emulator] " + instance.name() + " is stopped; " + liveness.clash());
        if (liveness.state() == EmulatorState.STOPPED) {
            if (!instance.canLaunch()) {
                // Nothing here starts it: the user may, and the wait below picks it up.
                Diag.log("[Emulator] '" + instance.name() + "' has no start command: waiting for it to be started");
            } else {
                Diag.log("[Emulator] starting '" + instance.name() + "'");
                if (progress != null) progress.accept("Starting " + instance.name() + "…");
                if (!EmulatorLauncher.launch(instance)) {
                    Diag.log("[Emulator] '" + instance.name() + "''s start command did not run");
                    return Optional.empty();
                }
            }
        }
        if (progress != null) progress.accept(instance.name() + " is booting — waiting for Android to finish starting…");
        // Only a fresh discovery says stopped: rediscover hands back the instance passed in when it finds nothing.
        Predicate<EmulatorInstance> stopped = instance.canLaunch()
                ? current -> current != instance && current.state() == EmulatorState.STOPPED
                : current -> false;
        Duration timeout = instance.platformId().bootTimeout();
        return awaitReady(instance, timeout, EmulatorReadiness::rediscover, EmulatorReadiness::isReady, stopped,
                START_GRACE);
    }

    /**
     * How long an instance its product calls stopped may stay so after its start before the start counts as failed:
     * time for Windows' permission prompt to be answered and the process to appear. Without it a start that never
     * ran (a declined prompt, or GameLoop's launcher doing nothing on 2026-11-11 before GameLoop was dropped) or
     * whose engine died at once (MEmu out of page file) was waited out for the whole boot timeout, then called
     * "still booting".
     */
    static final Duration START_GRACE = Duration.ofSeconds(45);

    /** Why {@code instance} didn't become ready in {@link #bringUp}, as a sentence a UI shows. */
    public static String notReady(EmulatorInstance instance) {
        if (HeldInstances.heldElsewhere(instance)) return HeldInstances.why(instance);
        EmulatorLiveness liveness = EmulatorLiveness.check(instance);
        String problem = liveness.problem(instance);
        if (problem != null) {
            return instance.name() + " can't be driven: " + problem + ".";
        }
        // The product's own word, not the port's: BlueStacks with several instances, or with ADB off, is up with its
        // port closed, and is then told apart by the boot message below.
        if (rediscover(instance).state() == EmulatorState.STOPPED) {
            return instance.canLaunch()
                    ? instance.name() + " didn't start: " + instance.brand() + " isn't running. If Windows asked for "
                    + "permission, allow it; if " + instance.brand() + " showed an error, it is in its window."
                    : instance.name() + " isn't running, and BotMaker can't start it: start it in "
                    + instance.brand() + ".";
        }
        return instance.name() + " didn't finish booting within " + instance.platformId().bootTimeout().toSeconds()
                + "s. It may still be starting — try again in a moment — or Android is up but ADB isn't answering, "
                + "which is usually an \"Allow USB debugging?\" prompt waiting inside the emulator.";
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
        return awaitReady(instance, timeout, rediscover, ready, current -> false, timeout);
    }

    /**
     * As above, giving up early once {@code stopped} has held for {@code grace} in a row: the product says nothing of
     * the instance runs, so no boot is going on to wait for.
     */
    static Optional<EmulatorInstance> awaitReady(EmulatorInstance instance, Duration timeout,
                                                 UnaryOperator<EmulatorInstance> rediscover,
                                                 Predicate<EmulatorInstance> ready,
                                                 Predicate<EmulatorInstance> stopped, Duration grace) {
        if (instance == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        long deadline = now + Math.max(0, timeout.toMillis());
        long stoppedSince = -1;
        EmulatorInstance current = instance;
        while (true) {
            current = rediscover.apply(current);
            if (ready.test(current)) {
                return Optional.of(current);
            }
            now = System.currentTimeMillis();
            if (!stopped.test(current)) {
                stoppedSince = -1;
            } else if (stoppedSince < 0) {
                stoppedSince = now;
            }
            if (stoppedSince >= 0 && now - stoppedSince >= grace.toMillis()) {
                Diag.log("[Emulator] " + instance.name() + " has stayed stopped " + grace.toSeconds()
                        + "s after its start: the start failed");
                return Optional.empty();
            }
            if (now >= deadline) {
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
