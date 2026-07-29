package com.botmaker.shared.session;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Single-sourced choice of nested-display {@link NestedSession.Backend} for a given launch — the one place both
 * the SDK bot runtime ({@code SessionBootstrap}) and Studio's launch surfaces agree on <em>which</em> backend a
 * target needs and whether it is installed. Living here (not in either consumer) is the repo's single-sourcing
 * rule: anything the SDK and Studio would each otherwise compute — backend choice, availability, the install
 * hint — belongs in shared so the two can't drift.
 *
 * <p><b>Why the choice is kind-driven.</b> A store launcher (Steam/Epic/Heroic/Faugus) and the Proton game
 * behind it — and a native {@code exe} game — boot a real GPU stack (Electron/Chromium, Vulkan/GL). Under
 * Xephyr's <em>software</em> GL that stack aborts (the observed Heroic SIGTRAP), so those kinds need
 * {@link NestedSession.Backend#GAMESCOPE}, which puts a real GPU inside the private display. A plain
 * {@code cli:} command has no such need and stays on the lighter {@link NestedSession.Backend#XEPHYR}.
 *
 * <p><b>No silent Xephyr fallback for a game.</b> When a game's required backend (gamescope) isn't on
 * {@code PATH}, {@link #availableBackendFor(LaunchSpec)} is empty — the loud-failure signal. Callers surface
 * {@link #installHint(NestedSession.Backend)} rather than falling back to a Xephyr that would crash the game.
 */
public final class SessionBackends {

    private SessionBackends() {}

    /**
     * The backend a target of this {@code spec} wants, irrespective of what's installed: game kinds
     * ({@link LaunchKind#STEAM STEAM}, {@link LaunchKind#EPIC EPIC}, {@link LaunchKind#HEROIC HEROIC},
     * {@link LaunchKind#FAUGUS FAUGUS}, {@link LaunchKind#EXE EXE}) → {@link NestedSession.Backend#GAMESCOPE};
     * everything else ({@link LaunchKind#CLI CLI}, {@link LaunchKind#EMULATOR_APP EMULATOR_APP},
     * {@link LaunchKind#UNKNOWN UNKNOWN}, and a {@code null} spec) → {@link NestedSession.Backend#XEPHYR}.
     */
    public static NestedSession.Backend preferredBackend(LaunchSpec spec) {
        if (spec == null) {
            return NestedSession.Backend.XEPHYR;
        }
        return switch (spec.kind()) {
            case STEAM, EPIC, HEROIC, FAUGUS, EXE -> NestedSession.Backend.GAMESCOPE;
            case CLI, EMULATOR_APP, UNKNOWN -> NestedSession.Backend.XEPHYR;
        };
    }

    /**
     * The {@link #preferredBackend(LaunchSpec)} for {@code spec}, but only if its host binary is actually on
     * {@code PATH}; empty otherwise. Empty means "the backend this target needs isn't installed" — the caller's
     * cue to fail loudly with {@link #installHint(NestedSession.Backend)}, <em>not</em> to drop to a different
     * backend (a game on Xephyr is exactly the crash this avoids).
     */
    public static Optional<NestedSession.Backend> availableBackendFor(LaunchSpec spec) {
        return availableBackendFor(spec, SessionBackends::onPath);
    }

    /**
     * Availability against an injected {@code binaryOnPath} probe — the testable seam behind
     * {@link #availableBackendFor(LaunchSpec)}, so a test can assert the kind→backend→availability chain without
     * a real {@code PATH}.
     */
    static Optional<NestedSession.Backend> availableBackendFor(LaunchSpec spec, Predicate<String> binaryOnPath) {
        NestedSession.Backend preferred = preferredBackend(spec);
        return binaryOnPath.test(preferred.binaryName()) ? Optional.of(preferred) : Optional.empty();
    }

    /** Whether {@code backend}'s host binary ({@link NestedSession.Backend#binaryName()}) is on {@code PATH}. */
    public static boolean isAvailable(NestedSession.Backend backend) {
        return onPath(backend.binaryName());
    }

    /**
     * A one-line, user-facing hint for making {@code backend} available — shown when a launch needs it but it
     * isn't installed. gamescope carries the "real GPU in the private display" rationale (the reason a game
     * can't just fall back to Xephyr); Xephyr the equivalent 2D note.
     */
    public static String installHint(NestedSession.Backend backend) {
        return switch (backend) {
            case GAMESCOPE -> "install gamescope to run games in a private background display "
                    + "(it provides a real GPU inside the nested display; Xephyr's software GL crashes games)";
            case XEPHYR -> "install Xephyr (the Xorg nested X server, e.g. the xorg-x11-server-Xephyr / "
                    + "xserver-xephyr package) to run in a private background display";
        };
    }

    /** Best-effort {@code PATH} probe for an executable named {@code binary}; false when {@code PATH} is unset. */
    private static boolean onPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, binary))) {
                return true;
            }
        }
        return false;
    }
}
