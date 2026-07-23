package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;

/**
 * The one entry point for "bring this launch target up" and "is it up?", given nothing but a parsed
 * {@link LaunchSpec}. This is what makes a launch target launchable from <em>either</em> consumer: the SDK's
 * {@code api.launch.Game}/{@code LaunchTarget} facades delegate their bodies here, and Studio's quick-launch
 * button calls it directly instead of re-deriving the protocol URLs and CLI ladders it cannot import.
 *
 * <p>{@link #isRunning(LaunchSpec)} is the host-observable part of the answer. The SDK layers one more
 * observation on top — the ambient capture source's own window — which shared cannot see, so it composes rather
 * than replaces this.
 */
public final class Launcher {

    private Launcher() {}

    /**
     * Brings {@code spec} up. Propagates the underlying launcher's failure (an uninstalled Steam, an
     * unregistered protocol handler) as a {@link RuntimeException} so a caller can report it; a caller that
     * only wants best-effort should use {@link #startQuietly(LaunchSpec)}.
     *
     * @return the spawned process when the process we started <em>is</em> the target ({@code exe:}/{@code cli:}),
     *         {@code null} for every kind that hands off to a launcher and exits
     */
    public static Process start(LaunchSpec spec) {
        if (spec == null) {
            Diag.log("[Target] start: no launch target configured");
            return null;
        }
        switch (spec.kind()) {
            case STEAM -> GameLauncher.steam(spec.token());
            case EPIC -> GameLauncher.epic(spec.token());
            case HEROIC -> GameLauncher.heroic(spec.token());
            case FAUGUS -> GameLauncher.faugus(spec.token());
            case EMULATOR_APP -> EmulatorAppLauncher.start(spec.emulatorPackage(), spec.emulatorInstance());
            case EXE, CLI -> {
                // The process we spawn is the target itself (no launcher hand-off), so its handle is worth
                // keeping as a first-hand "still running" answer.
                Process p = spec.kind() == LaunchKind.EXE
                        ? GameLauncher.exe(spec.token())
                        : GameLauncher.cli(spec.token());
                RunningProbe.record(spec.spec(), p);
                return p;
            }
            case UNKNOWN -> Diag.log("[Target] start: don't know how to launch '" + spec.spec() + "'");
        }
        return null;
    }

    /** {@link #start(LaunchSpec)} with the failure logged instead of thrown. Returns whether it got through. */
    public static boolean startQuietly(LaunchSpec spec) {
        try {
            start(spec);
            return true;
        } catch (Exception e) {
            Diag.error("[Target] start failed for " + (spec == null ? "(none)" : spec.spec())
                    + ": " + e.getMessage());
            return false;
        }
    }

    /** Brings {@code spec} up only if it isn't already running — the cold-start path. */
    public static void startIfNotRunning(LaunchSpec spec) {
        if (isRunning(spec)) {
            return;
        }
        start(spec);
    }

    /**
     * Restarts {@code spec} from a clean state. Only the kinds that can be force-stopped do more than
     * {@link #start(LaunchSpec)}: an {@code exe:}/{@code cli:} target is killed by process name first (a frozen
     * game won't exit on its own), and an emulator app is force-stopped over ADB.
     */
    public static void restart(LaunchSpec spec) {
        if (spec == null) {
            return;
        }
        switch (spec.kind()) {
            case EXE, CLI -> {
                String name = spec.fileName();
                if (!name.isBlank()) {
                    GameLauncher.kill(name);
                }
                start(spec);
            }
            case EMULATOR_APP -> EmulatorAppLauncher.restart(spec.emulatorPackage(), spec.emulatorInstance());
            default -> start(spec);
        }
    }

    /**
     * Whether {@code spec} is up right now, decided by layered <em>observation</em> — no timers, no cooldown,
     * no "we launched it recently so it must be running". Each layer answers from something the OS actually
     * reports, and the first "yes" wins:
     *
     * <ol>
     *   <li>Steam's own record of the app it is running ({@code steam:} only) — the single authority that is
     *       reported rather than inferred;</li>
     *   <li>ADB, for an emulator app — nothing on the host describes an app running inside an emulator;</li>
     *   <li>a process this JVM itself spawned for this spec still being alive (only {@code exe:}/{@code cli:}
     *       ever record one);</li>
     *   <li>any live process whose command line mentions the spec's {@link LaunchSpec#runningToken() token},
     *       deliberately matching the wrapper a launcher-started game runs under — and deliberately
     *       <em>not</em> matching the launcher's own UI, see {@link RunningProbe};</li>
     *   <li>a window titled after the token, enumerated from the OS.</li>
     * </ol>
     *
     * <p>Known gap, accepted knowingly: for the ~second between a launcher hand-off and the wrapper process
     * appearing, every layer is legitimately false, so a caller polling in a tight loop could launch twice.
     * Waiting on the game's window is the answer for that, and the {@code [Target]} traces make it visible.
     */
    public static boolean isRunning(LaunchSpec spec) {
        if (spec == null) {
            return false;
        }
        if (spec.kind() == LaunchKind.STEAM && RunningProbe.steamReportsRunning(spec.token())) {
            Diag.log("[Target] " + spec.spec() + ": Steam reports it as the running app");
            return true;
        }
        if (spec.kind() == LaunchKind.EMULATOR_APP) {
            return EmulatorAppLauncher.isRunning(spec.emulatorPackage(), spec.emulatorInstance());
        }
        if (RunningProbe.spawnedAlive(spec.spec())) {
            Diag.log("[Target] " + spec.spec() + ": the process we launched is still alive");
            return true;
        }
        String token = spec.runningToken();
        if (token == null || token.isBlank()) {
            return false;
        }
        if (RunningProbe.commandLineMentions(token)) {
            Diag.log("[Target] " + spec.spec() + ": a live process mentions '" + token + "'");
            return true;
        }
        if (RunningProbe.windowTitled(token)) {
            Diag.log("[Target] " + spec.spec() + ": a window is titled after '" + token + "'");
            return true;
        }
        return false;
    }
}
