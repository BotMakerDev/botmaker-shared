package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;

import java.util.List;
import java.util.Locale;
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

    private HostLauncherProbe() {}

    /** Whether an isolated launch of {@code kind} has to contend with a host launcher daemon at all. */
    public static boolean routesThroughDaemon(LaunchKind kind) {
        return kind != null && kind.routesThroughDaemon();
    }

    /**
     * Whether {@code kind}'s launcher UI is running on the host right now. Best-effort and total: an
     * unreadable process table answers {@code false}, because refusing a launch we could have attempted is
     * worse than attempting one that might fail.
     */
    public static boolean isRunning(LaunchKind kind) {
        return !running(kind).isEmpty();
    }

    /**
     * The host-desktop launcher processes that block an isolated launch of {@code kind} — the evidence behind
     * {@link #isRunning(LaunchKind)}, as the processes themselves.
     *
     * <p>Returning them rather than a bare boolean is what makes the refusal actionable. "Close Heroic and try
     * again" is only useful advice when Heroic is a window the user can see; measured, it routinely is not — a
     * tray-resident launcher, a Flatpak instance left behind by a previous run, or a background helper answers
     * this probe with nothing on screen to close, and the user is told to close something they cannot find. With
     * the handles in hand a caller can name the pid and offer {@link #closeHostLaunchers} instead of an
     * instruction.
     *
     * <p>Best-effort and total, for the same reason the boolean was: an unreadable process table answers "no
     * evidence", because refusing a launch we could have attempted is worse than attempting one that fails.
     */
    public static List<ProcessHandle> running(LaunchKind kind) {
        if (kind == null || !kind.routesThroughDaemon()) {
            return List.of();
        }
        Set<String> names = kind.processNames();
        String flatpakId = kind.flatpakAppId();
        long self = ProcessHandle.current().pid();
        try {
            return ProcessHandle.allProcesses()
                    .filter(p -> p.pid() != self)
                    .filter(p -> isLauncherUi(p, names, flatpakId))
                    .filter(p -> onHostDesktop(p, kind))
                    .toList();
        } catch (Exception e) {
            Diag.log("[Session] host-launcher scan for " + kind.id() + " failed: " + e.getMessage());
            return List.of();
        }
    }

    /** {@link #isRunning(LaunchKind)} for a parsed spec. */
    public static boolean isRunning(LaunchSpec spec) {
        return spec != null && isRunning(spec.kind());
    }

    /** {@link #running(LaunchKind)} for a parsed spec. */
    public static List<ProcessHandle> running(LaunchSpec spec) {
        return spec == null ? List.of() : running(spec.kind());
    }

    /**
     * Asks every host-desktop launcher UI of {@code kind} to quit — the action the refusal offers, so a caller
     * has something to <em>do</em> about the blocker rather than only something to say about it.
     *
     * <p>{@link ProcessHandle#destroy()}, not {@code destroyForcibly}: a launcher asked politely writes its
     * library state back and closes its own children; killed, it can leave a lock file that makes the next start
     * refuse. It acts only on what {@link #running(LaunchKind)} returned, so the same host-desktop filter that
     * decides a launcher is blocking is the one that decides it may be closed — a launcher inside one of our own
     * sessions is never touched.
     *
     * @return how many processes took the signal
     */
    public static int closeHostLaunchers(LaunchKind kind) {
        int closed = 0;
        for (ProcessHandle process : running(kind)) {
            try {
                if (process.destroy()) {
                    closed++;
                    Diag.log("[Session] asked " + productName(kind) + " (pid " + process.pid() + ") to quit");
                } else {
                    Diag.log("[Session] " + productName(kind) + " (pid " + process.pid()
                            + ") would not take a signal");
                }
            } catch (Exception e) {
                Diag.log("[Session] closing " + productName(kind) + " (pid " + process.pid() + ") failed: "
                        + e.getMessage());
            }
        }
        return closed;
    }

    /**
     * The user-facing reason an isolated launch of {@code kind} is being refused — single-sourced here so
     * Studio's Launch buttons and a headless bot run say the same thing (and name the same product). Names the
     * processes it actually found, so a launcher with no visible window is still findable.
     */
    public static String refusalMessage(LaunchKind kind) {
        return refusalMessage(kind, running(kind));
    }

    /** {@link #refusalMessage(LaunchKind)} over an already-taken observation, so the probe runs once. */
    public static String refusalMessage(LaunchKind kind, List<ProcessHandle> processes) {
        String product = productName(kind);
        return "Can't run " + kind.displayName().toLowerCase(Locale.ROOT) + "s in a private display while "
                + product + " is open: it is single-instance, so it would hand the launch to the copy already "
                + "running on your desktop and start the game there. Close " + product + " and try again"
                + describeProcesses(processes) + ".";
    }

    /**
     * " (pid 4711 heroic)" — what to close, for when there is no window to close. Empty when nothing was
     * observed, so the sentence still reads if the caller passes a stale or unavailable list.
     */
    private static String describeProcesses(List<ProcessHandle> processes) {
        if (processes == null || processes.isEmpty()) {
            return "";
        }
        String listed = processes.stream()
                .limit(4)
                .map(p -> "pid " + p.pid() + p.info().command()
                        .map(c -> " " + c.substring(c.lastIndexOf('/') + 1))
                        .orElse(""))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        String more = processes.size() > 4 ? " and " + (processes.size() - 4) + " more" : "";
        return " (" + listed + more + ")";
    }

    /** The launcher product's name as a human writes it — {@link LaunchKind#productName()}, null-tolerant. */
    private static String productName(LaunchKind kind) {
        return kind == null ? "the launcher" : kind.productName();
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
        Diag.log("[Session] ignoring pid " + process.pid() + " — " + productName(kind) + " is on "
                + ProcessOrigin.describe(process) + ", not the host desktop, so it can't swallow the launch");
        return false;
    }

    /**
     * Whether {@code process} is one of {@code names}' UIs: either its own program name (executable, or the
     * script an interpreter is running), or a Flatpak/Electron shell whose command line carries the launcher's
     * application id.
     *
     * <p>The id is stored once, in the canonical case {@code flatpak run} needs; the match is
     * case-insensitive, so it is <em>lowercased here</em> along with the command line rather than kept as a
     * second, differently-cased copy — which is exactly what it used to be.
     */
    private static boolean isLauncherUi(ProcessHandle process, Set<String> names, String flatpakAppId) {
        ProcessHandle.Info info = process.info();
        List<String> programNames = RunningProbe.programNames(info);
        for (String name : programNames) {
            // Through RunningProbe.named, not a plain set lookup: an AppImage or versioned binary
            // (Heroic-2.15.2.AppImage) must be recognised here exactly as the deny-list recognises it.
            if (RunningProbe.named(name, names) != null) {
                return true;
            }
        }
        if (flatpakAppId == null) {
            return false;
        }
        String commandLine = info.commandLine().map(s -> s.toLowerCase(Locale.ROOT)).orElse(null);
        return commandLine != null && commandLine.contains(flatpakAppId.toLowerCase(Locale.ROOT));
    }
}
