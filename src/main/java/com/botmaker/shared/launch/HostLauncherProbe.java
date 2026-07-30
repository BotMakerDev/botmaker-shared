package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whether a store launcher's own UI (Heroic, Steam, Faugus) is already running on the user's real desktop —
 * the one condition under which an <em>isolated</em> launch of that kind cannot possibly work.
 *
 * <p><b>Why this exists.</b> Every one of these launchers is single-instance: a second invocation doesn't start
 * a second launcher, it forwards its request to the one already running and exits. That primary instance lives
 * on {@code :0}, so it maps the game there — on the user's real desktop — no matter what {@code DISPLAY} we
 * handed our child. Before this probe, a nested session discovered that only by waiting out its whole window
 * timeout and then reaping a half-booted Electron (which dies with a {@code SIGTRAP} coredump when its X
 * connection disappears mid-boot). Asking first turns a two-minute mystery into an immediate, actionable "close
 * Heroic and retry".
 *
 * <p><b>"Running" means running on the host desktop.</b> The process table also contains the launchers inside
 * our own private sessions, and counting those inverted the feature: launch a game into a session, run the bot,
 * and it refused with "close Heroic" — naming the Heroic in that very session — then ran on {@code :0}. Every
 * candidate is therefore filtered through {@link ProcessOrigin#onHostDisplay}.
 *
 * <p>This is the mirror image of {@link RunningProbe}'s deny-list: that one excludes a launcher UI as evidence a
 * <em>game</em> is running; this one looks for exactly those processes. Both read the process table through
 * {@link RunningProbe#programNames} so a launcher shipped as a wrapper script, an Electron shell or a
 * {@code flatpak run} child is recognised identically by the two.
 */
public final class HostLauncherProbe {

    /**
     * The executable names each launcher's UI runs under, and the tokens its Flatpak/Electron form carries in
     * its command line. Only the kinds whose launch is <em>routed through a daemon</em> appear here:
     * {@code exe:}/{@code cli:} are our own child, and an emulator app is driven over ADB.
     */
    private static final Map<LaunchKind, Set<String>> DAEMON_NAMES = Map.of(
            LaunchKind.HEROIC, Set.of("heroic", "heroicgameslauncher"),
            LaunchKind.STEAM, Set.of("steam", "steamwebhelper"),
            LaunchKind.FAUGUS, Set.of("faugus-launcher", "faugus"));

    /** The Flatpak application ids of the same launchers — how the UI appears when installed that way. */
    private static final Map<LaunchKind, Set<String>> FLATPAK_IDS = Map.of(
            LaunchKind.HEROIC, Set.of("com.heroicgameslauncher.hgl"),
            LaunchKind.STEAM, Set.of("com.valvesoftware.steam"),
            LaunchKind.FAUGUS, Set.of("io.github.faugus.faugus-launcher"));

    private HostLauncherProbe() {}

    /** Whether an isolated launch of {@code kind} has to contend with a host launcher daemon at all. */
    public static boolean routesThroughDaemon(LaunchKind kind) {
        return kind != null && DAEMON_NAMES.containsKey(kind);
    }

    /**
     * Whether {@code kind}'s launcher UI is running on the host right now. Best-effort and total: an
     * unreadable process table answers {@code false}, because refusing a launch we could have attempted is
     * worse than attempting one that might fail.
     */
    public static boolean isRunning(LaunchKind kind) {
        Set<String> names = kind == null ? null : DAEMON_NAMES.get(kind);
        if (names == null) {
            return false;
        }
        Set<String> flatpakIds = FLATPAK_IDS.getOrDefault(kind, Set.of());
        long self = ProcessHandle.current().pid();
        try {
            return ProcessHandle.allProcesses()
                    .filter(p -> p.pid() != self)
                    .filter(p -> isLauncherUi(p, names, flatpakIds))
                    .anyMatch(p -> onHostDesktop(p, kind));
        } catch (Exception e) {
            Diag.log("[Session] host-launcher scan for " + kind.id() + " failed: " + e.getMessage());
            return false;
        }
    }

    /** {@link #isRunning(LaunchKind)} for a parsed spec. */
    public static boolean isRunning(LaunchSpec spec) {
        return spec != null && isRunning(spec.kind());
    }

    /**
     * The user-facing reason an isolated launch of {@code kind} is being refused — single-sourced here so
     * Studio's Launch buttons and a headless bot run say the same thing (and name the same product).
     */
    public static String refusalMessage(LaunchKind kind) {
        String product = displayName(kind);
        return "Can't run " + kind.displayName().toLowerCase(Locale.ROOT) + "s in a private display while "
                + product + " is open: it is single-instance, so it would hand the launch to the copy already "
                + "running on your desktop and start the game there. Close " + product + " and try again.";
    }

    /** The launcher product's name as a human writes it. */
    private static String displayName(LaunchKind kind) {
        if (kind == null) {
            return "the launcher";
        }
        return switch (kind) {
            case HEROIC -> "Heroic";
            case STEAM -> "Steam";
            case FAUGUS -> "Faugus Launcher";
            default -> "the launcher";
        };
    }

    /**
     * Whether a launcher UI we found is on the <b>host desktop</b> — the only place it can swallow an isolated
     * launch. A launcher running on one of our own private displays cannot: our child is handed that same
     * private {@code DISPLAY}, so a single-instance handoff lands where we wanted it anyway.
     *
     * <p>Traced rather than silent, for the same reason {@link RunningProbe}'s deny-list is: a false "the
     * launcher is open" is indistinguishable from a launch that did nothing. Measured live — launching a game
     * into a session and then running the bot refused with "close Heroic", naming the Heroic <em>inside that
     * session</em>.
     */
    private static boolean onHostDesktop(ProcessHandle process, LaunchKind kind) {
        if (ProcessOrigin.onHostDisplay(process)) {
            return true;
        }
        Diag.log("[Session] ignoring pid " + process.pid() + " — " + displayName(kind) + " is on "
                + ProcessOrigin.describe(process) + ", not the host desktop, so it can't swallow the launch");
        return false;
    }

    /**
     * Whether {@code process} is one of {@code names}' UIs: either its own program name (executable, or the
     * script an interpreter is running), or a Flatpak/Electron shell whose command line carries the launcher's
     * application id.
     */
    private static boolean isLauncherUi(ProcessHandle process, Set<String> names, Set<String> flatpakIds) {
        ProcessHandle.Info info = process.info();
        List<String> programNames = RunningProbe.programNames(info);
        for (String name : programNames) {
            if (names.contains(name)) {
                return true;
            }
        }
        if (flatpakIds.isEmpty()) {
            return false;
        }
        String commandLine = info.commandLine().map(s -> s.toLowerCase(Locale.ROOT)).orElse(null);
        if (commandLine == null) {
            return false;
        }
        for (String id : flatpakIds) {
            if (commandLine.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
