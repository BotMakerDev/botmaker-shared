package com.botmaker.shared;

import com.botmaker.shared.platform.Os;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Is this program actually on this machine?" — the one answer, because there were three private copies of it
 * (a {@code SessionBackends.onPath}, a {@code SpectacleCapture.isOnPath}, and one about to be written for the
 * isolation probe) and they had already started to differ on the cases below.
 *
 * <p>Two questions, deliberately separate: {@link #onPath} takes a bare program <em>name</em> and searches
 * {@code PATH}; {@link #exists} takes an argv[0] as written, which may be a name <em>or</em> a path — an
 * {@code exe:} launch target is routinely an absolute path, and searching {@code PATH} for
 * {@code /opt/game/game.x86_64} answers a confident, wrong "no".
 */
public final class Executables {

    /**
     * The nested X server used for 2D sessions. Capitalised because that <em>is</em> the executable's name —
     * kept apart from the lowercase {@code "xephyr"} wire id a project file persists, which must survive a
     * rename of the binary.
     */
    public static final String XEPHYR = "Xephyr";

    /**
     * The micro-compositor used for hardware-accelerated sessions — and, separately, to host the Wayland-only
     * Waydroid UI on an X11 desktop. Declared here because those two consumers sit on opposite sides of the
     * shared/session boundary and each had grown its own copy of the name.
     */
    public static final String GAMESCOPE = "gamescope";

    /** KDE's screenshot tool, a desktop-capture backend under Wayland ({@code capture.ToolCapture}). */
    public static final String SPECTACLE = "spectacle";

    /** The wlroots screenshot tool (Sway, Hyprland), a desktop-capture backend under Wayland. */
    public static final String GRIM = "grim";

    /** GNOME's screenshot tool, a desktop-capture backend under Wayland. */
    public static final String GNOME_SCREENSHOT = "gnome-screenshot";

    private Executables() {}

    /** Whether an executable named {@code binary} is on {@code PATH}; false when {@code PATH} is unset. */
    public static boolean onPath(String binary) {
        return find(binary).isPresent();
    }

    /**
     * <b>Where</b> on {@code PATH} an executable named {@code binary} is, rather than merely whether it is.
     *
     * <p>Both questions come up, and the answer is the same walk: {@code AdbTools} and {@code ScrcpyServer} each
     * need the file itself — one to run it, one to read the directory beside it — and each had grown a private
     * copy of this loop to get it. {@link #onPath} is now this, discarded.
     *
     * <p>Directories are excluded explicitly: a directory is "executable" in the POSIX sense (it can be
     * traversed), so a {@code bin/adb/} would otherwise be returned as the {@code adb} to run.
     *
     * <p>{@code PATH} only, exactly what a spawn by bare name searches, so a yes here is a spawn that works. A
     * caller that runs the file it gets back may look further with {@link #locate}.
     */
    public static Optional<File> find(String binary) {
        return find(binary, searchDirs(System.getenv("PATH"), false));
    }

    /**
     * Like {@link #find}, and on macOS also in the package managers' bin directories ({@link #MAC_EXTRA_DIRS})
     * that a Mac app's {@code PATH} leaves out. Only for a caller that spawns the returned file itself: the
     * bare name of something found there does not run.
     */
    public static Optional<File> locate(String binary) {
        return find(binary, searchDirs(System.getenv("PATH"), Os.current().isMac()));
    }

    /**
     * The argv[0] to run {@code binary} with: where {@link #locate} found it, else the bare name, so a spawn
     * that fails says what it looked for.
     */
    public static String resolve(String binary) {
        return locate(binary).map(File::getPath).orElse(binary);
    }

    /**
     * The package managers' bin directories a macOS app is not told about. An app opened from Finder or the
     * Dock gets launchd's {@code PATH} ({@code /usr/bin:/bin:/usr/sbin:/sbin}), not the shell's, so a
     * Homebrew {@code adb} or {@code cloudflared} the user can run in Terminal would read as missing.
     * Apple Silicon Homebrew, Intel Homebrew, MacPorts. Public for a host that searches its own directories too.
     */
    public static final List<String> MAC_EXTRA_DIRS = List.of("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin");

    /** The directories searched, in order: {@code PATH}'s, then with {@code macDirs} the ones it leaves out. */
    static List<String> searchDirs(String path, boolean macDirs) {
        List<String> dirs = new ArrayList<>();
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) dirs.add(dir);
            }
        }
        if (macDirs) {
            for (String extra : MAC_EXTRA_DIRS) {
                if (!dirs.contains(extra)) dirs.add(extra);
            }
        }
        return dirs;
    }

    static Optional<File> find(String binary, List<String> dirs) {
        if (binary == null || binary.isBlank()) {
            return Optional.empty();
        }
        for (String dir : dirs) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, binary);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate.toFile());
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code command} — an argv[0] exactly as a launch ladder spells it — can be run here: a path (it
     * contains a separator) is checked where it points, anything else is looked up on {@code PATH}.
     */
    public static boolean exists(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String c = command.trim();
        if (c.indexOf('/') >= 0 || c.indexOf('\\') >= 0) {
            try {
                return Files.isExecutable(Path.of(c));
            } catch (Exception e) {
                // An unparseable path is simply not something we can run.
                return false;
            }
        }
        return onPath(c);
    }
}
