package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings a game up on the host: the protocol-URL-then-CLI ladders for each store launcher, plus plain process
 * start/kill/probe by name. Everything here is pure OS interaction with no SDK types in sight, which is why it
 * lives in shared — the SDK's {@code api.launch.Game} facade (bot-facing, palette-visible) and Studio's
 * quick-launch button are both thin callers of these same methods rather than two copies of the ladders.
 *
 * <p>Each launch <b>logs before it invokes</b>. A detached process gives no feedback, so a launch that "does
 * nothing" — an unregistered {@code steam://} handler, a launcher that isn't installed — is otherwise invisible;
 * the trace is what makes it diagnosable instead of "nothing happened".
 */
public final class GameLauncher {

    private GameLauncher() {}

    /**
     * Starts an executable, optionally with arguments. The process is detached — its input/output is not tied
     * to the caller.
     *
     * @return the started process, the caller's first-hand handle on the target
     * @throws IllegalArgumentException if {@code executablePath} is null/blank
     * @throws RuntimeException         if the process could not be started
     */
    public static Process exe(String executablePath, String... args) {
        if (executablePath == null || executablePath.isBlank()) {
            throw new IllegalArgumentException("executablePath must not be empty");
        }
        List<String> command = new ArrayList<>();
        command.add(executablePath);
        if (args != null) {
            for (String arg : args) {
                if (arg != null) command.add(arg);
            }
        }
        try {
            Diag.log("[Game] launch: " + String.join(" ", command));
            return new ProcessBuilder(command).start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to launch '" + executablePath + "': " + e.getMessage(), e);
        }
    }

    /**
     * Runs a whole command line, split on whitespace into an executable + arguments.
     *
     * @return the started process, or {@code null} when the command line is empty (logged, not thrown — an
     *         empty command is a misconfiguration, not a reason to abort a bot)
     */
    public static Process cli(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            Diag.log("[Game] cli: empty command — nothing to launch");
            return null;
        }
        String[] parts = commandLine.trim().split("\\s+");
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return exe(parts[0], args);
    }

    /**
     * Launches a Steam game by appId. Opens {@code steam://rungameid/<appId>} and falls back to
     * {@code steam -applaunch <appId>} (requires {@code steam} on {@code PATH}).
     *
     * @throws IllegalArgumentException if {@code appId} is null/blank
     * @throws RuntimeException         if neither the Steam URL nor the CLI fallback could be invoked
     */
    public static void steam(String appId) {
        String id = require(appId, "appId");
        String uri = "steam://rungameid/" + id;
        Diag.log("[Game] launchSteam " + id + " → " + uri);
        if (UriLauncher.open(uri)) {
            Diag.log("[Game] launchSteam: opener invoked for " + uri);
            return;
        }
        Diag.log("[Game] launchSteam: opener declined " + uri + ", falling back to the Steam CLI");
        if (runFirst(LaunchCommands.steam(id))) {
            return;
        }
        throw new RuntimeException("Failed to launch Steam game '" + id + "'. Is Steam installed?");
    }

    /**
     * Launches an Epic Games title by its {@code AppName} (the launcher's manifest token, not the store title)
     * through {@code com.epicgames.launcher://apps/<appName>?action=launch}. Epic offers no supported CLI, so
     * this relies entirely on the protocol handler the launcher registers on install — see
     * {@link UriLauncher}'s {@code rundll32} note for why the Windows path matters here specifically.
     *
     * @throws IllegalArgumentException if {@code appName} is null/blank
     * @throws RuntimeException         if the protocol URL could not be invoked (launcher not installed?)
     */
    public static void epic(String appName) {
        String id = require(appName, "appName");
        String uri = "com.epicgames.launcher://apps/" + id + "?action=launch&silent=true";
        Diag.log("[Game] launchEpic " + id + " → " + uri);
        if (UriLauncher.open(uri)) {
            Diag.log("[Game] launchEpic: opener invoked for " + uri);
            return;
        }
        throw new RuntimeException("Failed to launch Epic game '" + id
                + "'. Is the Epic Games Launcher installed?");
    }

    /**
     * Launches a Heroic Games Launcher title by its {@code AppName}. Opens {@code heroic://launch/<appName>},
     * then falls back to {@code heroic --no-gui launch <appName>} and its Flatpak form (Heroic's most common
     * Linux install shape).
     *
     * @throws IllegalArgumentException if {@code appName} is null/blank
     * @throws RuntimeException         if neither the URL nor a CLI fallback could be invoked
     */
    public static void heroic(String appName) {
        String id = require(appName, "appName");
        String uri = "heroic://launch/" + id;
        Diag.log("[Game] launchHeroic " + id + " → " + uri);
        if (UriLauncher.open(uri)) {
            Diag.log("[Game] launchHeroic: opener invoked for " + uri);
            return;
        }
        Diag.log("[Game] launchHeroic: opener declined " + uri + ", falling back to the Heroic CLI");
        if (runFirst(LaunchCommands.heroic(id))) {
            return;
        }
        throw new RuntimeException("Failed to launch Heroic game '" + id
                + "'. Is the Heroic Games Launcher installed?");
    }

    /**
     * Launches a Faugus Launcher entry by its {@code gameid}. Faugus registers no protocol handler, so this
     * goes straight to {@code faugus-launcher --game <gameId>} and its Flatpak form. The id is matched exactly
     * against Faugus's {@code games.json}, so it must be the stored id, not the title.
     *
     * @throws IllegalArgumentException if {@code gameId} is null/blank
     * @throws RuntimeException         if neither CLI form could be invoked
     */
    public static void faugus(String gameId) {
        String id = require(gameId, "gameId");
        Diag.log("[Game] launchFaugus " + id);
        if (runFirst(LaunchCommands.faugus(id))) {
            return;
        }
        throw new RuntimeException("Failed to launch Faugus game '" + id + "'. Is Faugus Launcher installed?");
    }

    /**
     * Force-terminates every process whose executable matches {@code processName} — the "close the game" half
     * of a restart routine. Windows {@code taskkill /F /IM}, Linux/macOS {@code pkill -f}. Never throws when
     * there is simply no such process (that is a success for a kill); a genuinely un-runnable killer command is
     * logged, not raised, so a restart loop keeps going.
     *
     * @throws IllegalArgumentException if {@code processName} is null/blank
     */
    public static void kill(String processName) {
        String name = require(processName, "processName");
        String[] command = isWindows()
                ? new String[]{"taskkill", "/F", "/IM", name}
                : new String[]{"pkill", "-f", name};
        Diag.log("[Game] kill " + name + " → " + String.join(" ", command));
        try {
            int code = new ProcessBuilder(command).inheritIO().start().waitFor();
            // taskkill=128 / pkill=1 both mean "no matching process" — expected, not a failure.
            Diag.log("[Game] kill " + name + ": exit " + code
                    + (code == 0 ? " (terminated)" : " (nothing to kill / already gone)"));
        } catch (Exception e) {
            Diag.log("[Game] kill " + name + " failed to invoke: " + e.getMessage());
        }
    }

    /**
     * Whether any process whose executable matches {@code processName} is currently running. Uses
     * {@code tasklist} on Windows, {@code pgrep -f} elsewhere. Returns {@code false} (rather than throwing) if
     * the check itself cannot run.
     *
     * @throws IllegalArgumentException if {@code processName} is null/blank
     */
    public static boolean isProcessRunning(String processName) {
        String name = require(processName, "processName");
        try {
            if (isWindows()) {
                Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + name).start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor();
                return out.toLowerCase().contains(name.toLowerCase());
            }
            // `--` so a name starting with '-' isn't read as a flag. pgrep -f matches whole command lines,
            // which includes our own JVM when the name appears in its arguments — so read the pids and discard
            // our own rather than trusting the exit code.
            Process p = new ProcessBuilder("pgrep", "-f", "--", name).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            long self = ProcessHandle.current().pid();
            for (String line : out.split("\\R")) {
                String pid = line.trim();
                if (!pid.isEmpty() && Long.parseLong(pid) != self) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Diag.log("[Game] isRunning(" + name + ") check failed: " + e.getMessage());
            return false;
        }
    }

    /** Runs each argv in {@code ladder} in order, stopping at the first that starts; false if none did. */
    private static boolean runFirst(List<List<String>> ladder) {
        for (List<String> command : ladder) {
            if (tryStart(command)) {
                return true;
            }
        }
        return false;
    }

    /** Best-effort {@link ProcessBuilder#start()}; logs and returns false rather than throwing on failure. */
    private static boolean tryStart(List<String> command) {
        try {
            new ProcessBuilder(command).start();
            Diag.log("[Game] ran: " + String.join(" ", command));
            return true;
        } catch (Exception e) {
            Diag.log("[Game] command failed (" + command.get(0) + "): " + e.getMessage());
            return false;
        }
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        return value.trim();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
