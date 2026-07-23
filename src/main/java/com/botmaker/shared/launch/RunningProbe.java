package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.emulator.WindowsRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The observations behind "is this launch target up right now?" — each one a thing the OS actually reports,
 * never a timer or a cooldown. Kept apart from the launch contract because they are plumbing (process tables, a
 * registry key, a VDF file) rather than part of it, and kept in shared so the SDK's runtime probe and Studio's
 * quick-launch button cannot disagree about what "running" means.
 *
 * <p>Every method is best-effort and total: an unreadable process table, a missing registry key or an absent
 * window backend answers "no evidence" rather than throwing, because the caller's fallback — launching — is
 * always safe.
 */
public final class RunningProbe {

    /**
     * Processes a caller actually spawned, keyed by {@link LaunchSpec#spec()}. Only meaningful for the kinds
     * whose spawned process <em>is</em> the target ({@code exe:}/{@code cli:}); a {@code steam://} opener or
     * {@code faugus-launcher} hands off and exits within a second, so those deliberately never record.
     */
    private static final Map<String, ProcessHandle> SPAWNED = new ConcurrentHashMap<>();

    /** Steam writes the app id it is currently running here, as a DWORD (so {@code reg query} prints hex). */
    private static final String STEAM_KEY = "HKCU\\Software\\Valve\\Steam";
    private static final String RUNNING_APP_ID = "RunningAppID";
    private static final Pattern VDF_RUNNING_APP_ID =
            Pattern.compile("\"RunningAppID\"\\s+\"(\\d+)\"");

    /**
     * Executables that <em>know about</em> games rather than run them. A launcher's own UI carries the library
     * it browsed — a Heroic window has every {@code AppName} in its argv and its renderer's command line — so
     * {@link #commandLineMentions(String)} said "running" for any Heroic target the moment Heroic was merely
     * open, every caller skipped the cold launch, and the game never started. Quitting Heroic "fixed" it.
     *
     * <p>This is deliberately a list of <em>launchers</em>, not of games: the whole point of the command-line
     * layer is to match the wrappers a launcher-started game runs under ({@code reaper}, {@code proton},
     * {@code umu-run}, {@code wine}, {@code gogdl}), and those stay. Only the process whose job is to
     * <em>display</em> a library is excluded. Names are matched with the {@code .exe} suffix already stripped.
     */
    private static final Set<String> LAUNCHER_EXECUTABLES = Set.of(
            "heroic", "heroicgameslauncher", "epicgameslauncher", "epicwebhelper",
            "steam", "steamwebhelper", "steamservice", "faugus-launcher", "faugus");

    /**
     * Launcher UIs are Electron apps, so the process name is often just {@code electron} (or the Flatpak
     * wrapper's) with the real identity only in argv. A process named one of these is excluded when its
     * command line also names one of {@link #LAUNCHER_EXECUTABLES}.
     */
    private static final Set<String> ELECTRON_SHELLS = Set.of(
            "electron", "electron-bin", "bwrap", "flatpak", "chrome_crashpad_handler");

    /**
     * {@code legendary} is Heroic's Epic backend and is spawned for library work (auth, list, sync) as well as
     * for launching. Only the {@code launch} verb means a game is actually running under it.
     */
    private static final String LEGENDARY = "legendary";

    /**
     * Interpreters whose <em>first argument</em> is the real program. The JDK reports a script's
     * {@link ProcessHandle.Info#command()} as the interpreter, not the script — so a launcher shipped as a
     * wrapper script (which on Linux is the normal shape: the AUR/AppImage {@code heroic} and
     * {@code faugus-launcher} entry points, anything under {@code flatpak run}) would look like {@code bash}
     * and slip straight past the deny-list. Reading the script name back out is what makes the exclusion hold
     * for the packaging users actually have.
     */
    private static final Set<String> INTERPRETERS = Set.of(
            "sh", "bash", "dash", "zsh", "ksh", "python", "python3", "perl", "env");

    private RunningProbe() {}

    /** Remembers {@code process} as the live incarnation of {@code spec}. A null process clears the entry. */
    public static void record(String spec, Process process) {
        if (spec == null) return;
        if (process == null) {
            SPAWNED.remove(spec);
            return;
        }
        SPAWNED.put(spec, process.toHandle());
    }

    /** True when a process this JVM started for {@code spec} is still alive. */
    public static boolean spawnedAlive(String spec) {
        ProcessHandle handle = spec == null ? null : SPAWNED.get(spec);
        if (handle == null) {
            return false;
        }
        if (handle.isAlive()) {
            return true;
        }
        SPAWNED.remove(spec);
        return false;
    }

    /**
     * True when any live process other than this JVM — and other than a launcher's own UI, see
     * {@link #LAUNCHER_EXECUTABLES} — mentions {@code token} in its command line.
     *
     * <p>This is the primary layer precisely <em>because</em> it matches wrappers: a Steam game runs under
     * {@code reaper SteamLaunch AppId=<id> -- … proton …}, a Heroic one under {@code legendary launch <appName>},
     * a Faugus one under {@code umu-run} with the game id in its environment-carrying argv. The token is the
     * target's own launch identity, so whichever layer of wrapping is on top, one of them still carries it.
     *
     * <p>Caveat: the JDK exposes {@link ProcessHandle.Info#commandLine()} only for processes the current user can
     * inspect — on Windows that means same-user processes, which is the case for a game the user launched.
     */
    public static boolean commandLineMentions(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String needle = token.trim().toLowerCase(Locale.ROOT);
        long self = ProcessHandle.current().pid();
        try {
            return ProcessHandle.allProcesses()
                    .filter(p -> p.pid() != self)
                    .anyMatch(p -> mentions(p, needle));
        } catch (Exception e) {
            Diag.log("[Target] process scan for '" + token + "' failed: " + e.getMessage());
            return false;
        }
    }

    /** Whether {@code process} is evidence of a running game carrying {@code needle} (already lower-cased). */
    private static boolean mentions(ProcessHandle process, String needle) {
        ProcessHandle.Info info = process.info();
        String commandLine = info.commandLine().orElse(null);
        if (commandLine == null || !commandLine.toLowerCase(Locale.ROOT).contains(needle)) {
            return false;
        }
        String reason = launcherReason(info, commandLine.toLowerCase(Locale.ROOT));
        if (reason != null) {
            // Traced, not silent: the whole reason this deny-list exists is that a false "already running" is
            // indistinguishable from a launch that did nothing. Now the console says which process caused it.
            Diag.log("[Target] ignoring pid " + process.pid() + " as " + reason
                    + " — it names '" + needle + "' but is not running it");
            return false;
        }
        return true;
    }

    /**
     * Why {@code info} should not count as a running game, or {@code null} when it should. Split out so the
     * three cases — a launcher UI, an Electron shell hosting one, {@code legendary} doing library work — read
     * as the three separate observations they are.
     */
    private static String launcherReason(ProcessHandle.Info info, String lowerCommandLine) {
        for (String name : programNames(info)) {
            if (LAUNCHER_EXECUTABLES.contains(name)) {
                return "the " + name + " launcher UI";
            }
            if (ELECTRON_SHELLS.contains(name)
                    && LAUNCHER_EXECUTABLES.stream().anyMatch(lowerCommandLine::contains)) {
                return "an " + name + " process hosting a launcher UI";
            }
            if (LEGENDARY.equals(name) && !hasArgument(info, "launch")) {
                return "legendary doing library work rather than launching";
            }
        }
        return null;
    }

    /**
     * The names this process could reasonably be called: its executable, plus the script it is interpreting
     * when the executable is a shell (see {@link #INTERPRETERS}). Lower-cased, {@code .exe} stripped.
     */
    private static List<String> programNames(ProcessHandle.Info info) {
        String exe = baseName(info.command().orElse(null));
        if (exe == null) {
            return List.of();
        }
        if (!INTERPRETERS.contains(exe)) {
            return List.of(exe);
        }
        String script = baseName(firstNonFlagArgument(info));
        return script == null ? List.of(exe) : List.of(exe, script);
    }

    /** The first argument that isn't a flag — for an interpreter, the script it was asked to run. */
    private static String firstNonFlagArgument(ProcessHandle.Info info) {
        String[] args = info.arguments().orElse(null);
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            if (arg != null && !arg.isBlank() && !arg.startsWith("-")) {
                return arg;
            }
        }
        return null;
    }

    /** A path's trailing segment, lower-cased with any {@code .exe} suffix stripped; {@code null} if blank. */
    private static String baseName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
        name = name.toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    /** Whether {@code verb} appears as a whole argument (not as a substring of a path or an app name). */
    private static boolean hasArgument(ProcessHandle.Info info, String verb) {
        String[] args = info.arguments().orElse(null);
        if (args == null) {
            // No argv visible (a permissions or platform limitation) — don't invent a reason to exclude it.
            return true;
        }
        return List.of(args).contains(verb);
    }

    /**
     * True when a window titled after {@code token} is open — asked of the OS through
     * {@code NativeController.getAllWindows()}, <em>not</em> of any capture source, so it answers whatever
     * the project happens to capture (the desktop, a monitor, an emulator).
     */
    public static boolean windowTitled(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String needle = token.trim().toLowerCase(Locale.ROOT);
        try {
            for (GenericWindow w : NativeControllerFactory.get().getAllWindows()) {
                String title = w.getTitle();
                if (title != null && title.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Includes the UnsupportedOperationException macOS's absent backend throws.
            Diag.log("[Target] window scan for '" + token + "' failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * True when Steam itself reports {@code appId} as the app it is running — the one authority that is observed
     * rather than inferred. Absent/unreadable, or a different app, both answer {@code false} so the caller falls
     * through to the other layers (the key is not written for every launch path, e.g. non-Steam shortcuts).
     */
    public static boolean steamReportsRunning(String appId) {
        if (appId == null || appId.isBlank()) {
            return false;
        }
        String running = readSteamRunningAppId();
        return running != null && running.equals(appId.trim());
    }

    /** Steam's currently-running app id as a decimal string, or {@code null} when it can't be read. */
    private static String readSteamRunningAppId() {
        String fromRegistry = WindowsRegistry.read(STEAM_KEY, RUNNING_APP_ID);
        if (fromRegistry != null && !fromRegistry.isBlank()) {
            return decimal(fromRegistry);
        }
        return fromSteamVdf();
    }

    /** Linux: Steam mirrors the same value into {@code ~/.steam/registry.vdf}. */
    private static String fromSteamVdf() {
        for (String candidate : new String[]{".steam/registry.vdf", ".steam/steam/registry.vdf"}) {
            Path vdf = Path.of(System.getProperty("user.home", ""), candidate);
            try {
                if (!Files.isReadable(vdf)) {
                    continue;
                }
                Matcher m = VDF_RUNNING_APP_ID.matcher(Files.readString(vdf, StandardCharsets.UTF_8));
                if (m.find()) {
                    return m.group(1);
                }
            } catch (Exception e) {
                Diag.log("[Target] reading " + vdf + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    /** {@code reg query} prints a DWORD as {@code 0x23a}; normalise both forms to a decimal string. */
    private static String decimal(String raw) {
        String v = raw.trim();
        try {
            return v.toLowerCase(Locale.ROOT).startsWith("0x")
                    ? Long.toString(Long.parseLong(v.substring(2), 16))
                    : Long.toString(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Test seam: forgets every recorded spawn. */
    public static void clearSpawned() {
        SPAWNED.clear();
    }

    /** The live handle recorded for {@code spec}, if any — exposed for tests. */
    public static Optional<ProcessHandle> spawned(String spec) {
        return Optional.ofNullable(SPAWNED.get(spec));
    }
}
