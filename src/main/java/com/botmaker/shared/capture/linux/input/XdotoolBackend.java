package com.botmaker.shared.capture.linux.input;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.sun.jna.Pointer;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real pointer/keyboard input via the {@code xdotool} CLI — the backend used when synthetic events aren't
 * accepted, i.e. games.
 *
 * <p><b>Why a CLI and not more JNA.</b> xdotool drives XTEST, the same extension {@link XTestBackend} binds
 * directly, but it does one thing we can't do in-process: it chains a whole gesture into a
 * <em>single</em> invocation — {@code mousemove --sync X Y click B mousemove restore} positions, clicks and
 * puts the pointer back with one round trip and xdotool's own bookkeeping of the prior position. Measured at
 * ~2 ms per spawn, which is cheaper than the settle delay the gesture needs anyway.
 *
 * <p><b>What this backend deliberately does not do.</b> It never passes {@code --window}. That variant uses
 * {@code XSendEvent}, whose events carry the {@code send_event} flag games reject — the exact failure this
 * backend exists to work around. {@code --window} also takes its coordinates from the <em>current pointer
 * position</em> rather than an argument, and {@code mousemove --window} moves the real cursor regardless, so
 * it isn't even a cursor-safe way to click a specific point. Coordinate-accurate background clicking is
 * {@link XSendEventBackend}'s job; this backend's job is landing the click at all.
 *
 * <p>Consequence: real pointer input hits whatever is <b>topmost</b> at the coordinate, so
 * {@link #clickWindow} raises the target first. That is visible to the user and unavoidable on X11 for a
 * client that drops synthetic events.
 */
public final class XdotoolBackend implements LinuxInputBackend {

	/** Cap on any single xdotool call; {@code windowactivate --sync} can otherwise block on a wedged client. */
	private static final long TIMEOUT_MS = 2000;

	private final Pointer display;

	private XdotoolBackend(Pointer display) {
		this.display = display;
	}

	/**
	 * A backend if {@code xdotool} is on the PATH, else {@code null} so backend selection can fall through to
	 * the in-process {@link XTestBackend} (same XTEST mechanism, minus the atomic restore).
	 */
	public static XdotoolBackend tryCreate(Pointer display) {
		try {
			Process p = new ProcessBuilder("xdotool", "version")
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				p.destroyForcibly();
				return null;
			}
			return p.exitValue() == 0 ? new XdotoolBackend(display) : null;
		} catch (IOException e) {
			return null; // not installed — the caller logs the install hint once
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	@Override
	public String name() {
		return "xdotool";
	}

	@Override
	public boolean preservesCursor() {
		// It warps and puts the pointer back, which is not the same as never touching it: the click still
		// lands on whatever is topmost, so this cannot drive a background window.
		return false;
	}

	@Override
	public void clickWindow(Pointer window, int relX, int relY, int button) {
		Rectangle rect = X11Utils.getWindowGeometry(display, window);
		if (rect == null) {
			Diag.error("[Linux/xdotool] no geometry for target window — click skipped.");
			return;
		}
		// XTEST hits the topmost window at the point, so the target has to be on top first.
		if (window != null && Pointer.nativeValue(window) != 0) {
			run("windowactivate", "--sync", Long.toString(Pointer.nativeValue(window)));
		}
		clickScreen(rect.x + relX, rect.y + relY, button);
	}

	@Override
	public void clickScreen(int xAbs, int yAbs, int button) {
		// One invocation: position, click, and restore the pointer to where the user left it.
		run("mousemove", "--sync", Integer.toString(xAbs), Integer.toString(yAbs),
			"click", Integer.toString(button),
			"mousemove", "restore");
	}

	@Override
	public void move(int xAbs, int yAbs) {
		run("mousemove", "--sync", Integer.toString(xAbs), Integer.toString(yAbs));
	}

	@Override
	public void button(int button, boolean press) {
		run(press ? "mousedown" : "mouseup", Integer.toString(button));
	}

	@Override
	public void key(int keysym, boolean press) {
		String name = X11.INSTANCE.XKeysymToString(keysym);
		if (name == null) {
			Diag.error("[Linux/xdotool] no X name for keysym 0x" + Integer.toHexString(keysym) + " — key skipped.");
			return;
		}
		run(press ? "keydown" : "keyup", name);
	}

	@Override
	public void scroll(int amount) {
		if (amount == 0) {
			return;
		}
		int button = amount > 0 ? 4 : 5; // 4 = up/away, 5 = down/toward
		run("click", "--repeat", Integer.toString(Math.abs(amount)), Integer.toString(button));
	}

	/** The pointer's absolute position, or {@code null} if xdotool couldn't report it. */
	public Point cursorPosition() {
		String out = capture("getmouselocation", "--shell");
		if (out == null) {
			return null;
		}
		Integer x = null;
		Integer y = null;
		for (String line : out.split("\n")) {
			if (line.startsWith("X=")) {
				x = parse(line.substring(2));
			} else if (line.startsWith("Y=")) {
				y = parse(line.substring(2));
			}
		}
		return (x == null || y == null) ? null : new Point(x, y);
	}

	private static Integer parse(String s) {
		try {
			return Integer.valueOf(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Run an xdotool command, discarding output. Failures are diagnostics, never exceptions. */
	private void run(String... args) {
		exec(args, false);
	}

	/** Run an xdotool command and return its stdout, or {@code null} on any failure. */
	private String capture(String... args) {
		return exec(args, true);
	}

	private String exec(String[] args, boolean wantOutput) {
		List<String> command = new ArrayList<>(args.length + 1);
		command.add("xdotool");
		command.addAll(List.of(args));
		Process p = null;
		try {
			ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
			if (!wantOutput) {
				pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			}
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			p = pb.start();
			String out = wantOutput ? new String(p.getInputStream().readAllBytes()) : null;
			if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
				p.destroyForcibly();
				Diag.error("[Linux/xdotool] timed out: " + String.join(" ", command));
				return null;
			}
			if (p.exitValue() != 0) {
				Diag.error("[Linux/xdotool] exit " + p.exitValue() + ": " + String.join(" ", command));
				return null;
			}
			return out;
		} catch (IOException e) {
			Diag.error("[Linux/xdotool] failed: " + String.join(" ", command) + " — " + e.getMessage());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			if (p != null) {
				p.destroyForcibly();
			}
			return null;
		}
	}
}
