package com.botmaker.shared.session;

import com.botmaker.shared.capture.linux.LinuxController;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.launch.LaunchSpec;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The gamescope counterpart to {@link NestedSessionLiveTest}: the same background-input proof, run against the
 * {@link NestedSession.Backend#GAMESCOPE} display server instead of Xephyr. gamescope embeds its own Xwayland
 * on a real GPU, so this is the harness the {@link GamescopeDisplay} "unverified on the dev box" note asks for —
 * it exercises the standalone-host bring-up (stderr display-number parsing, {@link DisplayReadiness}), launches
 * a client into {@code :N}, drives that display's private pointer without moving the real {@code :0} cursor,
 * and additionally asserts the one thing that distinguishes this backend: the session advertises
 * {@link Capability#HARDWARE_GL}/{@link Capability#VULKAN}.
 *
 * <p><b>Opt-in and self-skipping, exactly like the Xephyr suite</b> — it runs only with {@code -Dbotmaker.live=true}
 * <em>and</em> a usable {@code DISPLAY} + {@code gamescope} on {@code PATH}. The existing {@code session-live.yml}
 * CI installs Xephyr but not gamescope (GitHub runners have no GPU), so there this class self-skips; it is meant
 * to be run on a real gamescope/GPU box, where it is the missing live proof for the 3D backend.
 *
 * <p>gamescope bring-up flags vary by box (DRM vs. nested, backend choice). The default standalone argv
 * ({@link GamescopeDisplay#defaultCommand}) is used unless overridden with {@code -Dbotmaker.gamescope.args="…"}
 * (space-split, passed verbatim as the gamescope argv) — e.g. {@code -Dbotmaker.gamescope.args="gamescope
 * --backend sdl -W 1280 -H 720"} to force the nested SDL backend on a desktop that isn't a DRM session.
 */
class NestedSessionGamescopeLiveTest {

	@Test
	void aNestedGamescopeSessionDrivesItsPrivatePointerWithoutTouchingTheRealCursor() throws Exception {
		assumeLive();
		String hostDisplay = System.getenv("DISPLAY");
		LinuxController hostRead = LinuxController.forDisplay(hostDisplay);
		try {
			assertNotNull(hostRead.cursorPosition(), "should be able to read the real cursor on " + hostDisplay);

			NestedSession session = NestedSession.start(gamescopeOptions(1280, 720));
			String nested = session.displayName();
			try {
				assertNotEquals(hostDisplay, nested, "the nested display must not be the real one");
				assertTrue(nested.matches(":\\d+"), "nested display should look like :N, was " + nested);
				assertTrue(session.has(Capability.BACKGROUND_CLICK),
					"a nested session must honestly advertise BACKGROUND_CLICK");

				// The gamescope-specific promise: its embedded Xwayland renders on the real GPU, so the session
				// advertises hardware GL and Vulkan (Xephyr's software path advertises neither).
				assertTrue(session.has(Capability.HARDWARE_GL),
					"a gamescope session should advertise HARDWARE_GL");
				assertTrue(session.has(Capability.VULKAN),
					"a gamescope session should advertise VULKAN");

				// A client with a private DISPLAY, launched as our own child, maps a window on :N.
				session.launch(LaunchSpec.parse("cli:xmessage -center BotMakerGamescopeLiveTest"));
				assertNotNull(session.attached(), "a window should have appeared on " + nested);

				// Drive :N's private pointer to a distinctive target and confirm the injection landed there.
				//
				// Unlike Xephyr, gamescope's pointer is compositor-mediated, and the first live run on a real box
				// pinned down two measured consequences (see the ROADMAP entry for the session, both reproduced
				// with plain xdotool against a hand-started gamescope):
				//   * the warp is applied a frame later, so reading it back in the same breath returns the OLD
				//     position — hence the settle loop rather than an immediate read;
				//   * a settled read comes back a constant +2,+2 off the requested point while gamescope is
				//     compositing its own cursor over a mapped window (640,360 → 642,362; 200,150 → 202,152 …),
				//     so this asserts a tolerance rather than equality. Relative motion is exact.
				Point target = new Point(640, 360);
				session.pointer().moveAbsolute(target.x, target.y);
				Point onNested = awaitPointerNear(session, target, POINTER_TOLERANCE_PX, 2_000);
				assertNotNull(onNested, "should be able to read the :N pointer");
				assertTrue(near(onNested, target, POINTER_TOLERANCE_PX),
					"the :N pointer should have landed within " + POINTER_TOLERANCE_PX + "px of " + target
						+ ", was " + onNested);
				session.pointer().click(1);

				// Capture flows through the :N-bound controller.
				//
				// KNOWN FAILURE, first live gamescope run: this call does not merely return null — the X server
				// answers X_GetImage with BadMatch, and because nothing installs an XSetErrorHandler, Xlib's
				// default handler calls exit(1) and takes the whole JVM (a bot!) with it. Two separate defects,
				// both scheduled next: gamescope's Xwayland needs a capture path that works (its windows are
				// composited, so the XComposite/XGetImage route this uses doesn't apply), and no X error should
				// ever be able to kill the process.
				assertNotNull(session.capture(), "capturing the attached :N window should yield a frame");

				// The isolation assertion: our injection reached :N (above) but did NOT leak to the real display.
				// See NestedSessionLiveTest for why this is an inequality (robust on a live, in-use desktop), not
				// a strict before==after.
				Point after = hostRead.cursorPosition();
				assertNotNull(after, "should still be able to read the real cursor");
				assertNotEquals(target, after,
					"driving the :N pointer must not move the real " + hostDisplay + " cursor (input leaked to :0)");
			} finally {
				session.close();
			}
			// close() reaps the whole tree — the private display is gone.
			assertTrue(displayGoneWithin(nested, 5_000), nested + " should be torn down after close()");
		} finally {
			hostRead.close();
		}
	}

	// --- guards & helpers ---

	/** How far a settled gamescope pointer read may sit from the requested point (measured: a constant 2px). */
	private static final int POINTER_TOLERANCE_PX = 4;

	/** Poll the {@code :N} pointer until it is within {@code tolerance} of {@code target}, or time out. */
	private static Point awaitPointerNear(NestedSession session, Point target, int tolerance, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		Point last = null;
		while (System.currentTimeMillis() < deadline) {
			last = session.pointer().position();
			if (last != null && near(last, target, tolerance)) {
				return last;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return last;
	}

	private static boolean near(Point actual, Point target, int tolerance) {
		return Math.abs(actual.x - target.x) <= tolerance && Math.abs(actual.y - target.y) <= tolerance;
	}

	/** Opt-in (so a plain {@code mvn test} never spins up gamescope) and only where the live stack is present. */
	private static void assumeLive() {
		assumeTrue(Boolean.getBoolean("botmaker.live"),
			"opt-in live test — run with -Dbotmaker.live=true on a real gamescope/GPU box");
		String display = System.getenv("DISPLAY");
		assumeTrue(display != null && !display.isBlank(), "needs a DISPLAY");
		assumeTrue(onPath(NestedSession.Backend.GAMESCOPE.binaryName()), "needs gamescope on PATH");
	}

	/**
	 * The default standalone gamescope options at {@code width}x{@code height}, unless {@code -Dbotmaker.gamescope.args}
	 * overrides the whole argv (space-split) — the {@link GamescopeDisplay} bring-up note's per-box escape hatch.
	 */
	private static NestedSession.Options gamescopeOptions(int width, int height) {
		NestedSession.Options base = NestedSession.Options.gamescope(width, height);
		String override = System.getProperty("botmaker.gamescope.args");
		if (override == null || override.isBlank()) {
			return base;
		}
		return base.withGamescopeCommand(override.trim().split("\\s+"));
	}

	private static boolean onPath(String exe) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(File.pathSeparator)) {
			if (new File(dir, exe).canExecute()) {
				return true;
			}
		}
		return false;
	}

	/** Poll until {@code name} can no longer be opened (gamescope has exited), or the deadline passes. */
	private static boolean displayGoneWithin(String name, long ms) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < deadline) {
			Pointer d = X11.INSTANCE.XOpenDisplay(name);
			if (d == null) {
				return true;
			}
			X11.INSTANCE.XCloseDisplay(d);
			Thread.sleep(150);
		}
		return false;
	}
}
