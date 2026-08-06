package com.botmaker.shared.platform;

/**
 * The handful of environment variable names the session stack reads and writes.
 *
 * <p>Constants rather than an enum on purpose: these are names the OS and the desktop specs define, not a
 * domain set this codebase gets to close. There is nothing to switch over and nothing to parse — the value is
 * simply that {@code "DBUS_SESSION_BUS_ADDRESS"} is spelled once, since a typo in any of the six sites that
 * used to spell it produces a session whose payload silently talks to the <em>host</em> bus.
 *
 * <p>It lives in shared because {@link com.botmaker.shared.launch.ProcessOrigin} reads {@code DISPLAY} out of
 * {@code /proc/<pid>/environ} while {@code botmaker-session} writes it into the child environment: producer
 * and reader are in different modules, and shared is the only one both can see.
 */
public final class SessionEnv {

    /** The X display a process draws on — the variable every question about "which desktop?" comes down to. */
    public static final String DISPLAY = "DISPLAY";

    /**
     * The Wayland socket. Deliberately set to the empty string for a session's children unless the session
     * hosts a compositor of its own: a client offered both usually prefers Wayland, and the host compositor is
     * exactly what a private session exists to stay out of.
     */
    public static final String WAYLAND_DISPLAY = "WAYLAND_DISPLAY";

    /** Where the per-user runtime sockets live; its absence is what says "there is no user systemd here". */
    public static final String XDG_RUNTIME_DIR = "XDG_RUNTIME_DIR";

    /** The session bus a process talks to — and, through it, which Flatpak portal re-spawns its children. */
    public static final String DBUS_SESSION_BUS_ADDRESS = "DBUS_SESSION_BUS_ADDRESS";

    private SessionEnv() {}

    /** {@code NAME=value}, an entry as it appears in {@code /proc/<pid>/environ} or a {@code --setenv=}. */
    public static String assignment(String name, String value) {
        return name + "=" + value;
    }

    /** {@code NAME=} — the prefix to match an entry on when scanning an environment block. */
    public static String prefix(String name) {
        return name + "=";
    }
}
