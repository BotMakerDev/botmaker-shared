package com.botmaker.shared.session;

import com.botmaker.shared.capture.linux.input.PointerWarp;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-sourced backend chooser: kind→backend, and availability filtered by an injected {@code PATH} probe.
 * Pure — no real X server or {@code PATH} involved.
 */
class SessionBackendsTest {

    private static LaunchSpec spec(LaunchKind kind, String token) {
        return new LaunchSpec(kind, token);
    }

    @Test
    void gameKindsPreferGamescope() {
        assertEquals(NestedSession.Backend.GAMESCOPE, SessionBackends.preferredBackend(spec(LaunchKind.STEAM, "570")));
        assertEquals(NestedSession.Backend.GAMESCOPE, SessionBackends.preferredBackend(spec(LaunchKind.EPIC, "Fortnite")));
        assertEquals(NestedSession.Backend.GAMESCOPE, SessionBackends.preferredBackend(spec(LaunchKind.HEROIC, "Firestone")));
        assertEquals(NestedSession.Backend.GAMESCOPE, SessionBackends.preferredBackend(spec(LaunchKind.FAUGUS, "battlenet")));
        assertEquals(NestedSession.Backend.GAMESCOPE, SessionBackends.preferredBackend(spec(LaunchKind.EXE, "/usr/bin/game")));
    }

    @Test
    void cliAndEmulatorAndUnknownStayOnXephyr() {
        assertEquals(NestedSession.Backend.XEPHYR, SessionBackends.preferredBackend(spec(LaunchKind.CLI, "echo hi")));
        assertEquals(NestedSession.Backend.XEPHYR, SessionBackends.preferredBackend(spec(LaunchKind.EMULATOR_APP, "com.app@Pie64")));
        assertEquals(NestedSession.Backend.XEPHYR, SessionBackends.preferredBackend(spec(LaunchKind.UNKNOWN, "whatever")));
    }

    @Test
    void nullSpecDefaultsToXephyr() {
        assertEquals(NestedSession.Backend.XEPHYR, SessionBackends.preferredBackend(null));
    }

    @Test
    void availableWhenTheRequiredBinaryIsOnPath() {
        Predicate<String> installed = onPath("gamescope", "Xephyr");
        assertEquals(Optional.of(NestedSession.Backend.GAMESCOPE),
                SessionBackends.availableBackendFor(spec(LaunchKind.HEROIC, "Firestone"), installed));
        assertEquals(Optional.of(NestedSession.Backend.XEPHYR),
                SessionBackends.availableBackendFor(spec(LaunchKind.CLI, "echo hi"), installed));
    }

    @Test
    void emptyWhenAGameNeedsGamescopeButItIsMissing() {
        // Only Xephyr installed: a game (wants gamescope) has no available backend — the loud-failure signal,
        // never a silent drop to the Xephyr that would crash it.
        Predicate<String> onlyXephyr = onPath("Xephyr");
        assertTrue(SessionBackends.availableBackendFor(spec(LaunchKind.HEROIC, "Firestone"), onlyXephyr).isEmpty());
        // A cli target is fine on the installed Xephyr.
        assertEquals(Optional.of(NestedSession.Backend.XEPHYR),
                SessionBackends.availableBackendFor(spec(LaunchKind.CLI, "echo hi"), onlyXephyr).map(b -> b));
        assertFalse(SessionBackends.availableBackendFor(spec(LaunchKind.CLI, "echo hi"), onlyXephyr).isEmpty());
    }

    @Test
    void xephyrGetsOpenboxWhenInstalledAndGamescopeNeverDoes() {
        // A bare Xephyr has no EWMH, so nothing takes input focus — openbox is what makes key injection land.
        assertEquals(List.of("openbox", "--sm-disable"),
                SessionBackends.windowManagerFor(NestedSession.Backend.XEPHYR, onPath("openbox")));
        // Absent openbox is a soft degrade to WM-less, not a failure.
        assertTrue(SessionBackends.windowManagerFor(NestedSession.Backend.XEPHYR, onPath()).isEmpty());
        // gamescope IS the window manager for its Xwayland; a second one would fight it for the selection.
        assertTrue(SessionBackends.windowManagerFor(NestedSession.Backend.GAMESCOPE, onPath("openbox")).isEmpty());
    }

    @Test
    void onlyGamescopeNeedsTheFocusRelativeWarpCorrection() {
        // gamescope's Xwayland routes injected motion through the focused surface, so a root-absolute target
        // lands offset by the focus window's origin (measured: (2,2), i.e. every click 2px off). Xephyr — like
        // every real X server — honours the root-absolute contract and must not be "corrected".
        assertEquals(PointerWarp.FOCUS_RELATIVE, SessionBackends.pointerWarpFor(NestedSession.Backend.GAMESCOPE));
        assertEquals(PointerWarp.ROOT_ABSOLUTE, SessionBackends.pointerWarpFor(NestedSession.Backend.XEPHYR));
    }

    @Test
    void installHintNamesTheBackend() {
        assertTrue(SessionBackends.installHint(NestedSession.Backend.GAMESCOPE).toLowerCase().contains("gamescope"));
        assertTrue(SessionBackends.installHint(NestedSession.Backend.XEPHYR).toLowerCase().contains("xephyr"));
    }

    private static Predicate<String> onPath(String... installed) {
        Set<String> present = Set.of(installed);
        return present::contains;
    }
}
