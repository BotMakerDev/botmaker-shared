package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.capture.WindowMatch;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which open window is a launch target's game — never decided by its title alone (2026-10-13). A title only says
 * a window is <em>named</em> after the game: that day Studio's own "BotMaker Blocks - Firestone" made a closed
 * Firestone look running, the launch was skipped, and the bot clicked in Studio. A wiki tab or a chat channel
 * carries the name just as well.
 *
 * <p>Two things prove a window is the game's, in this order:
 * <ol>
 *   <li>it is the window the bot names, a {@link WindowIdentity}: its class — X11's {@code WM_CLASS}, the same on
 *       every computer whatever the language or version — and its exact title too when that class is generic;</li>
 *   <li>its owning process is the game's: one the launch target's evidence counts
 *       ({@link RunningProbe#processesRunning}), the process BotMaker spawned for it, or a child of either. This
 *       is how a bot that names no window yet learns it ({@link WindowIdentity#of}). A process inside a Flatpak
 *       sandbox (Heroic's) gives its window the sandbox's own pid — a small number every sandbox reuses — so that
 *       id counts only for a window also titled after the game.</li>
 * </ol>
 * The answer is a {@link Verdict}: {@link Proven} names the window; {@link Unsure} lists the windows only
 * titled after the game — the ones to ask a person about (Studio's window picker), and that a run with nobody to
 * ask never attaches to; {@link None} when there is neither.
 */
public final class GameWindows {

    private GameWindows() {
    }

    /** What the open windows say about a launch target's game. */
    public sealed interface Verdict permits Proven, Unsure, None {
    }

    /** {@code window} is the game's: the bot names it, or the game's process owns it. */
    public record Proven(GenericWindow window) implements Verdict {
    }

    /** No window is proved the game's; {@code candidates} are titled after it, likeliest first. */
    public record Unsure(List<GenericWindow> candidates) implements Verdict {
        public Unsure {
            candidates = List.copyOf(candidates);
        }
    }

    /** No window is the game's or named after it. */
    public record None() implements Verdict {
    }

    /** The game's processes: their ids on the host, and inside their own sandbox where that differs. */
    record GamePids(Set<Long> host, Set<Long> sandbox) {
        static final GamePids NONE = new GamePids(Set.of(), Set.of());
    }

    /**
     * The verdict for {@code spec} over the windows open now; {@code game} is the window the bot names, or null.
     * Best-effort: a failed scan answers {@link None}.
     */
    public static Verdict of(LaunchSpec spec, WindowIdentity game) {
        return scan(spec, game, true);
    }

    /**
     * {@link #of} without looking for the game's processes — for a caller that has just found none
     * ({@link Launcher#isRunning}), so only a window {@code game} names can prove anything.
     */
    static Verdict ofIdentity(LaunchSpec spec, WindowIdentity game) {
        return scan(spec, game, false);
    }

    /** The verdict for {@code spec} (null for none: no process, no title) over {@code windows}. */
    public static Verdict of(LaunchSpec spec, WindowIdentity game, List<GenericWindow> windows) {
        List<String> tokens = spec == null ? List.of() : Launcher.runningTokens(spec);
        return judge(windows, game, spec == null ? GamePids.NONE : gamePids(spec, tokens), tokens);
    }

    /**
     * The window of {@code windows} that {@code game} names, with no process scan — the cheap check a capture
     * makes each time once it knows the game's window.
     */
    public static Optional<GenericWindow> named(WindowIdentity game, List<GenericWindow> windows) {
        return proven(judge(windows, game, GamePids.NONE, List.of()));
    }

    private static Verdict scan(LaunchSpec spec, WindowIdentity game, boolean withProcesses) {
        if (spec == null) return new None();
        try {
            List<GenericWindow> windows = NativeControllerFactory.get().getAllWindows();
            if (withProcesses) return of(spec, game, windows);
            return judge(windows, game, GamePids.NONE, Launcher.runningTokens(spec));
        } catch (Exception e) {
            // Includes the UnsupportedOperationException macOS's absent backend throws.
            Diag.log("[Target] window scan for " + spec.spec() + " failed: " + e.getMessage());
            return new None();
        }
    }

    /**
     * The verdict over {@code windows} given the window the bot names ({@code game}, or null), the game's
     * processes ({@code pids}) and what its windows may be titled after ({@code titles}). Pure, so the rule is
     * testable without a desktop.
     *
     * <p>Several proved windows — a splash and the game — are ranked by title, then by size. While the game runs,
     * a window whose process is known and is not the game's is no candidate; with no game process at all, every
     * window titled after it is.
     */
    static Verdict judge(List<GenericWindow> windows, WindowIdentity game, GamePids pids, List<String> titles) {
        List<GenericWindow> usable = windows == null ? List.of()
                : windows.stream().filter(w -> w != null && area(w.getRect()) > 0 && !WindowMatch.isOwn(w)).toList();
        List<GenericWindow> titled = byTitle(usable, titles);

        if (game != null) {
            List<GenericWindow> named = usable.stream().filter(game::matches).toList();
            if (!named.isEmpty()) return new Proven(best(named, titles));
        }
        List<GenericWindow> owned = usable.stream()
                .filter(w -> pids.host().contains(w.getPid())
                        || (pids.sandbox().contains(w.getPid()) && titled.contains(w)))
                .toList();
        if (!owned.isEmpty()) return new Proven(best(owned, titles));

        List<GenericWindow> candidates = titled.stream()
                .filter(w -> pids.host().isEmpty() || w.getPid() == GenericWindow.NO_PID)
                .toList();
        return candidates.isEmpty() ? new None() : new Unsure(candidates);
    }

    /** {@code verdict}'s window when it is {@link Proven}. */
    public static Optional<GenericWindow> proven(Verdict verdict) {
        return verdict instanceof Proven(GenericWindow window) ? Optional.of(window) : Optional.empty();
    }

    /** {@code window} for a trace: its title, class and owning process. */
    public static String describe(GenericWindow window) {
        String program = window.getPid() == GenericWindow.NO_PID ? "?"
                : ProcessHandle.of(window.getPid()).flatMap(p -> p.info().command()).orElse("?");
        return "'" + window.getTitle() + "' (class " + Optional.ofNullable(window.getWindowClass()).orElse("?")
                + ", pid " + (window.getPid() == GenericWindow.NO_PID ? "?" : window.getPid()) + ", " + program + ")";
    }

    // ------------------------------------------------------------------

    private static GenericWindow best(List<GenericWindow> proven, List<String> titles) {
        return byTitle(proven, titles).stream().findFirst()
                .orElseGet(() -> proven.stream().max(Comparator.comparingLong(w -> area(w.getRect()))).orElseThrow());
    }

    /** The windows titled after any of {@code titles}, most distinctive title first, each once. */
    private static List<GenericWindow> byTitle(List<GenericWindow> windows, List<String> titles) {
        Set<GenericWindow> out = new LinkedHashSet<>();
        for (String title : titles) {
            out.addAll(WindowMatch.ranked(windows, title));
        }
        return new ArrayList<>(out);
    }

    /** The game's processes now — what its tokens' evidence counts, what we spawned, their children. */
    private static GamePids gamePids(LaunchSpec spec, List<String> tokens) {
        Set<Long> host = new LinkedHashSet<>();
        Set<Long> sandbox = new LinkedHashSet<>();
        Stream.concat(tokens.stream().flatMap(token -> RunningProbe.processesRunning(token).stream()),
                        RunningProbe.spawned(spec.spec()).filter(ProcessHandle::isAlive).stream())
                .flatMap(process -> Stream.concat(Stream.of(process), process.descendants()))
                .forEach(process -> {
                    host.add(process.pid());
                    sandboxPid(process.pid()).ifPresent(sandbox::add);
                });
        return new GamePids(host, sandbox);
    }

    /** The id {@code pid} has inside its own pid namespace (a Flatpak sandbox), when that differs; Linux only. */
    private static Optional<Long> sandboxPid(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        try {
            return innermostPid(Files.readString(status)).filter(inner -> inner != pid);
        } catch (java.io.IOException e) {
            return Optional.empty();   // no /proc (not Linux), or the process just exited
        }
    }

    /** The last id of {@code /proc/<pid>/status}'s {@code NSpid:} line — the process's id in its own namespace. */
    static Optional<Long> innermostPid(String status) {
        return status.lines().filter(line -> line.startsWith("NSpid:")).findFirst()
                .map(line -> line.substring("NSpid:".length()).trim().split("\\s+"))
                .map(ids -> ids[ids.length - 1])
                .flatMap(GameWindows::parsePid);
    }

    private static Optional<Long> parsePid(String id) {
        try {
            return Optional.of(Long.parseLong(id));
        } catch (NumberFormatException e) {
            Diag.log("[Target] unreadable NSpid entry '" + id + "' — a sandboxed game's window cannot be proved by pid");
            return Optional.empty();
        }
    }

    private static long area(Rectangle rect) {
        return rect == null ? 0 : (long) Math.max(0, rect.width) * Math.max(0, rect.height);
    }
}
