package com.botmaker.shared;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /** KDE's screenshot tool, the desktop-capture backend under Wayland. */
    public static final String SPECTACLE = "spectacle";

    private Executables() {}

    /** Whether an executable named {@code binary} is on {@code PATH}; false when {@code PATH} is unset. */
    public static boolean onPath(String binary) {
        if (binary == null || binary.isBlank()) {
            return false;
        }
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
