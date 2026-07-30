package com.botmaker.shared.launch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Where does this pid live?</b> — the reading the launch probes were missing. Both of them ask a question
 * about the <em>host desktop</em> ("is the launcher UI open?", "is this game already running?") while looking at
 * a process table that also contains our own private sessions and their leftovers, so both answered "yes" for
 * processes that were never on {@code :0}.
 *
 * <p>Two things go wrong without it, both measured live:
 * <ul>
 *   <li>Launch a game into a private session, then run the bot: {@link HostLauncherProbe} found the Heroic
 *       <em>inside our own session</em>, {@link LaunchIsolation} refused with "close Heroic and try again", and
 *       the bot ran on the user's real desktop. The working setup was the one that got refused.</li>
 *   <li>Long after the launcher was closed, {@link RunningProbe} still reported the game running — the process
 *       was a remnant of a session whose owning JVM had gone, so the bot skipped its launch entirely.</li>
 * </ul>
 *
 * <p>Two independent signals, because neither alone is enough. {@code DISPLAY} out of {@code /proc/<pid>/environ}
 * is the decisive one — it is what actually decides which desktop a process draws on, and it stays true for the
 * Flatpak-portal-escaped children that are deliberately <em>not</em> in our cgroup. The cgroup name is the cheap
 * fallback for when {@code environ} can't be read, and the only way to recognise a <em>remnant</em> (the session
 * id carries its owning JVM's pid, so a dead owner is readable off the cgroup path).
 *
 * <p>Best-effort and total, like every probe here: no {@code /proc} (Windows, macOS), an unreadable file or an
 * absent variable all answer "no evidence" — never an exception, and never a guess that would make a caller
 * refuse a launch it could have attempted.
 */
public final class ProcessOrigin {

    /**
     * The prefix every transient unit of a nested session carries. Defined here rather than only at the spawn
     * site because this class <em>parses it back</em> out of a cgroup path: the producer (the session reaper's
     * {@code --unit=}/{@code --slice=} names) and the reader must agree, and they are in different packages.
     */
    public static final String SESSION_UNIT_PREFIX = "botmaker-sess-";

    /**
     * A session unit inside a cgroup path, capturing the session id and its owning JVM pid. The id shape
     * {@code s<pid>-<seq>} is a contract with the reaper (which parses the same pid back out of a slice name to
     * sweep orphans); matching the pid here is what makes {@link #isSessionRemnant} possible at all.
     */
    private static final Pattern SESSION_UNIT =
            Pattern.compile(Pattern.quote(SESSION_UNIT_PREFIX) + "(s(\\d+)-\\d+)");

    private ProcessOrigin() {}

    /** The {@code DISPLAY} this JVM is on — the "host desktop" every question here is relative to. */
    public static String hostDisplay() {
        return System.getenv("DISPLAY");
    }

    /**
     * The {@code DISPLAY} of {@code pid} as it was when the process started, or {@code null} when it has none or
     * cannot be read (a foreign user's process, a kernel thread, no {@code /proc}).
     */
    public static String displayOf(long pid) {
        for (String entry : environOf(pid)) {
            if (entry.startsWith("DISPLAY=")) {
                String value = entry.substring("DISPLAY=".length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * Whether {@code process} draws on the host desktop rather than on one of our private displays.
     *
     * <p>Answers {@code true} unless something positively says otherwise, which is the direction that preserves
     * behaviour where the reading is unavailable: on Windows, for a process whose {@code environ} we may not
     * read, or when this JVM itself has no {@code DISPLAY} to compare against.
     */
    public static boolean onHostDisplay(ProcessHandle process) {
        if (process == null) {
            return false;
        }
        String theirs = displayOf(process.pid());
        if (theirs == null) {
            // No display reading at all — fall back to the cgroup: a member of one of our sessions is not on :0.
            return sessionIdOf(process.pid()) == null;
        }
        String host = hostDisplay();
        String hostNumber = displayNumber(host);
        String theirNumber = displayNumber(theirs);
        if (hostNumber == null || theirNumber == null) {
            return true;
        }
        return hostNumber.equals(theirNumber);
    }

    /**
     * The BotMaker session {@code pid} belongs to ({@code s<pid>-<seq>}), or {@code null} when it is not in one.
     * Read off the cgroup path, so it holds for anything systemd placed in the session's slice — and, by the same
     * token, misses a child a Flatpak portal re-parented out of it (which is what {@link #displayOf} is for).
     */
    public static String sessionIdOf(long pid) {
        Matcher m = SESSION_UNIT.matcher(readProc(pid, "cgroup"));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Whether {@code pid} is a leftover of a <em>dead</em> session: in a session cgroup whose owning JVM is gone.
     * Such a process is running, but it is not evidence of anything a caller asked about — the session that
     * launched it no longer exists, and nobody is driving it.
     */
    public static boolean isSessionRemnant(long pid) {
        Matcher m = SESSION_UNIT.matcher(readProc(pid, "cgroup"));
        if (!m.find()) {
            return false;
        }
        long owner;
        try {
            owner = Long.parseLong(m.group(2));
        } catch (NumberFormatException e) {
            return false;
        }
        return !ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false);
    }

    /** How to describe where a process lives, for a log line that has to be actionable. */
    public static String describe(ProcessHandle process) {
        if (process == null) {
            return "nowhere";
        }
        String display = displayOf(process.pid());
        String session = sessionIdOf(process.pid());
        if (display != null && session != null) {
            return display + " (BotMaker session " + session + ")";
        }
        if (display != null) {
            return display;
        }
        return session != null ? "BotMaker session " + session : "an unreadable display";
    }

    /**
     * The screen-independent number of an X display spec: {@code :1.0} and {@code :1} are the same display, and
     * a remote {@code host:1} is still display 1. {@code null} when it isn't an X display spec at all.
     */
    static String displayNumber(String display) {
        if (display == null || display.isBlank()) {
            return null;
        }
        String s = display.trim();
        int colon = s.lastIndexOf(':');
        if (colon < 0 || colon == s.length() - 1) {
            return null;
        }
        String rest = s.substring(colon + 1);
        int dot = rest.indexOf('.');
        String number = dot >= 0 ? rest.substring(0, dot) : rest;
        return number.isBlank() ? null : number.toLowerCase(Locale.ROOT);
    }

    /** {@code /proc/<pid>/environ} split on its NUL separators, or empty when it can't be read. */
    private static String[] environOf(long pid) {
        String raw = readProc(pid, "environ");
        return raw.isEmpty() ? new String[0] : raw.split("\0");
    }

    /** One {@code /proc/<pid>/<file>}, or {@code ""} for anything unreadable — including "there is no /proc". */
    private static String readProc(long pid, String file) {
        Path path = Path.of("/proc", Long.toString(pid), file);
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Deliberately silent: unreadable is the routine case (a process that exited between the scan and
            // this read, another user's process, no /proc at all) and it is read once per candidate process per
            // scan — logging it would bury the one line that matters, the "ignoring pid N" below.
            return "";
        }
    }
}
