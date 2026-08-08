package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wait for an emulator to become driveable, against injected probes — no socket, no emulator. What is
 * worth pinning is the pair of facts that made an app launch silently do nothing: an open ADB port is not a
 * booted Android, and the instance you hold before a launch may not be the one that comes up.
 */
class EmulatorReadinessTest {

    private static EmulatorInstance at(String host) {
        return new EmulatorInstance(PlatformId.WAYDROID, "Waydroid", host, 5555);
    }

    /** The re-resolve that returns whatever it is given — "discovery still says the same thing". */
    private static final UnaryOperator<EmulatorInstance> UNCHANGED = i -> i;

    @Test
    void aTimeoutIsAnEmptyAnswerRatherThanAThrow() {
        Optional<EmulatorInstance> result = EmulatorReadiness.awaitReady(
                at("192.168.240.112"), Duration.ZERO, UNCHANGED, i -> false);

        assertTrue(result.isEmpty());
    }

    @Test
    void aReadyInstanceIsReturnedWithoutWaiting() {
        EmulatorInstance instance = at("192.168.240.112");
        Optional<EmulatorInstance> result = EmulatorReadiness.awaitReady(
                instance, Duration.ofSeconds(30), UNCHANGED, i -> true);

        assertSame(instance, result.orElseThrow());
    }

    @Test
    void theInstanceItReturnsIsTheReDiscoveredOneNotTheOnePassedIn() {
        // The case this exists for: Waydroid's address is a property of a *running* container, so the
        // instance discovered while it was down can name the fallback address rather than the live one.
        // Waiting on the stale snapshot is waiting on nothing.
        EmulatorInstance stale = at(WaydroidStatus.DEFAULT_IP);
        EmulatorInstance live = at("192.168.240.200");
        Predicate<EmulatorInstance> ready = i -> i.endpoint().equals(live.endpoint());

        Optional<EmulatorInstance> result = EmulatorReadiness.awaitReady(
                stale, Duration.ofSeconds(30), i -> live, ready);

        assertEquals(live.endpoint(), result.orElseThrow().endpoint());
    }

    @Test
    void aNullInstanceIsNeverReadyAndNeverWaitedOn() {
        assertFalse(EmulatorReadiness.portOpen(null));
        assertTrue(EmulatorReadiness.awaitReady(null, Duration.ofSeconds(1)).isEmpty());
    }

    @Test
    void reDiscoveryFallsBackToTheInstanceItWasGivenWhenNothingMatches() {
        // Discovery finding no match (a config that disappeared mid-wait) must not turn into a null or a
        // throw inside a poll loop — the caller's own timeout is the thing that ends the wait. Deliberately
        // an UNKNOWN product with an invented name: no real discovery can ever produce one, so this asserts
        // the fallback on a developer's machine whether or not an emulator happens to be installed on it.
        EmulatorInstance unknown = new EmulatorInstance(PlatformId.UNKNOWN, "no-such-instance", "10.0.0.1", 1);
        assertSame(unknown, EmulatorReadiness.rediscover(unknown));
    }

    @Test
    void everyProductGetsAPositiveBootBudgetAndWaydroidGetsTheLongest() {
        // A values() sweep so a product added later has to decide this rather than inherit a guess.
        for (PlatformId id : PlatformId.values()) {
            assertTrue(id.bootTimeout().toMillis() > 0, id.name());
            if (id != PlatformId.WAYDROID) {
                assertTrue(PlatformId.WAYDROID.bootTimeout().compareTo(id.bootTimeout()) > 0, id.name());
            }
        }
    }

    @Test
    void monkeyReportsItsOwnFailureOnStdout() {
        // The output was discarded until now, which is why "the app isn't installed" and "the app started"
        // looked identical to the launcher.
        assertTrue(AdbDevice.startedApp("Events injected: 1\n## Network stats: elapsed time=3ms"));
        assertFalse(AdbDevice.startedApp(
                "** No activities found to run, monkey aborted."));
        assertFalse(AdbDevice.startedApp("Error: Intent matched no activities"));
        assertFalse(AdbDevice.startedApp(""));
        assertFalse(AdbDevice.startedApp(null));
    }

    @Test
    void discoveryStillReturnsAListEvenOnAMachineWithNoEmulators() {
        // Guards the re-discovery path's assumption that Platforms.discoverAll never throws.
        List<EmulatorInstance> found = Platforms.discoverAll();
        assertTrue(found != null);
    }
}
