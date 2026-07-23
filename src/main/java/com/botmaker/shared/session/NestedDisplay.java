package com.botmaker.shared.session;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.linux.X11;
import com.sun.jna.Pointer;

import java.io.File;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * A nested Xephyr X server the bot owns, and the machinery to bring it up race-free. The display number is
 * <b>not</b> chosen by us and <b>never</b> by scanning {@code /tmp/.X11-unix} (which races under parallel
 * starts): we pass Xephyr {@code -displayfd 1} and let <em>it</em> pick a free number and write it back, then
 * read that number off the (reaper-inherited) stdout. Readiness is gated on an actual {@code XOpenDisplay}
 * succeeding — never a {@code sleep}.
 *
 * <p>The server process is owned by the session's {@link SessionReaper}; this type only starts it and reports
 * whether it is still {@link #alive()}.
 */
final class NestedDisplay {

	/** How long to wait for Xephyr to write its display number, then for that display to accept a connection. */
	private static final long START_TIMEOUT_MS = 10_000;
	private static final long POLL_MS = 100;

	private final String displayName;
	private final int width;
	private final int height;
	private final Process server;

	private NestedDisplay(String displayName, int width, int height, Process server) {
		this.displayName = displayName;
		this.width = width;
		this.height = height;
		this.server = server;
	}

	/** The display this server owns, e.g. {@code ":9"}. */
	String displayName() {
		return displayName;
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	/** Whether the X server process is still running — the signal a session's {@code DEAD} health rests on. */
	boolean alive() {
		return server.isAlive();
	}

	/**
	 * Launch Xephyr at {@code width}x{@code height} through {@code reaper}, read back the display number it
	 * chose, and block until that display accepts a connection.
	 *
	 * @throws SessionStartException if Xephyr never reports a number, or the display never becomes connectable
	 */
	static NestedDisplay startXephyr(SessionReaper reaper, int width, int height) throws SessionStartException {
		File out;
		Process server;
		try {
			out = SessionReaper.tempOutputFile("botmaker-xephyr-");
			// -displayfd 1: Xephyr picks a free display and writes "<n>\n" to fd 1. -ac drops access control
			// (a private local display), -noreset keeps it alive across the last client, -resizeable lets the
			// game size the root. No ":N" is passed — the whole point is that Xephyr, not us, allocates it.
			server = reaper.launch("xephyr",
				List.of("Xephyr", "-displayfd", "1", "-screen", width + "x" + height,
					"-ac", "-noreset", "-resizeable"),
				Map.of(),
				Redirect.appendTo(out));
		} catch (Exception e) {
			throw new SessionStartException("could not launch Xephyr (is it installed?): " + e.getMessage(), e);
		}

		String number = awaitDisplayNumber(out, server);
		String display = ":" + number;
		awaitConnectable(display, server);
		Diag.log("[Session] nested display " + display + " up (" + width + "x" + height + ")");
		return new NestedDisplay(display, width, height, server);
	}

	/** Poll the server's stdout file until it holds the display number Xephyr wrote, or time out / it dies. */
	private static String awaitDisplayNumber(File out, Process server) throws SessionStartException {
		long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			String n = readNumber(out);
			if (n != null) {
				return n;
			}
			if (!server.isAlive()) {
				throw new SessionStartException("Xephyr exited before reporting a display number "
					+ "(exit " + server.exitValue() + ")");
			}
			sleep();
		}
		throw new SessionStartException("Xephyr did not report a display number within "
			+ START_TIMEOUT_MS + "ms");
	}

	/** The first whitespace-delimited integer token in the file, or {@code null} if none is there yet. */
	private static String readNumber(File out) {
		try {
			String s = new String(Files.readAllBytes(out.toPath()), StandardCharsets.US_ASCII).trim();
			if (s.isEmpty()) {
				return null;
			}
			String first = s.split("\\s+")[0];
			// Guard against a partial write: only accept an all-digit token.
			return first.chars().allMatch(Character::isDigit) ? first : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** Block until {@code XOpenDisplay(display)} succeeds — the readiness gate that replaces a fixed sleep. */
	private static void awaitConnectable(String display, Process server) throws SessionStartException {
		long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			Pointer d = null;
			try {
				d = X11.INSTANCE.XOpenDisplay(display);
			} catch (Throwable ignored) {
				// X11 not linkable / transient — treated the same as "not ready yet".
			}
			if (d != null) {
				X11.INSTANCE.XCloseDisplay(d);
				return;
			}
			if (!server.isAlive()) {
				throw new SessionStartException("Xephyr on " + display + " died before it accepted connections");
			}
			sleep();
		}
		throw new SessionStartException("nested display " + display + " never accepted a connection");
	}

	private static void sleep() {
		try {
			Thread.sleep(POLL_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
