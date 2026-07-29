package com.botmaker.shared.capture.linux.input;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.botmaker.shared.capture.linux.XTest;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * XTest backend — device-level injection through the XTEST extension: {@code XTestFakeMotionEvent} warps the
 * one shared cursor to the target, then clicks. {@link #preservesCursor()} is false, so on {@code :0} it's an
 * explicit opt-in ({@code botmaker.linux.input=xtest}); on a bot-owned nested display it is the default,
 * because there the "shared" cursor is the bot's alone (see {@code NestedSession}).
 *
 * <p><b>Phase 4 hardening.</b> Three properties a naive warp-and-click lacks:
 * <ul>
 *   <li><b>Observable timing</b> — every click follows move → {@code XSync} (so the server has actually
 *       applied the motion before the button goes down) → {@link InputTiming#motionSettleMs()} → press →
 *       {@link InputTiming#pressHoldMs()} → release, so toolkits/games that sample input on a frame timer
 *       don't drop it.</li>
 *   <li><b>Deterministic Unicode keys</b> — XTest injects keycodes, so a character the active layout maps to
 *       no keycode is undeliverable; {@link Keymap} borrows a spare keycode for it and restores the layout
 *       afterwards.</li>
 *   <li><b>No stuck input</b> — every key/button this backend presses is tracked, and {@link #releaseHeld()}
 *       (called from the typing path's {@code finally} and on {@link #close()}) guarantees nothing is left
 *       held down if a sequence is interrupted mid-stroke — the classic "went insane after N actions" cause.</li>
 * </ul>
 * <p><b>Warp coordinates are not universal.</b> {@link #move} injects through {@link PointerWarp}: on a normal
 * server the target goes in as-is, while on gamescope's Xwayland — which reads injected motion as
 * <em>window-relative</em> — the focused window's origin is subtracted first, so a bot's root-absolute click
 * target still lands on the pixel it matched. See {@link PointerWarp} for the measurements.
 *
 * <p>It does <b>not</b> restore the cursor position afterwards (on Wayland the prior position can't be read — see
 * {@link com.botmaker.shared.capture.linux.LinuxController}).
 */
public final class XTestBackend implements LinuxInputBackend {

	private final Pointer display;
	private final InputTiming timing;
	/** How this server reads warp coordinates — {@link PointerWarp#FOCUS_RELATIVE} needs a correction. */
	private final PointerWarp warp;

	/** Lazily built (needs the range/row-width read once); {@code null} until the first out-of-map character. */
	private Keymap keymap;

	/** Keycodes and buttons currently pressed by this backend, so {@link #releaseHeld} can let them all go. */
	private final Set<Integer> heldKeys = new LinkedHashSet<>();
	private final Set<Integer> heldButtons = new LinkedHashSet<>();

	public XTestBackend(Pointer display) {
		this(display, InputTiming.DEFAULT, PointerWarp.ROOT_ABSOLUTE);
	}

	public XTestBackend(Pointer display, PointerWarp warp) {
		this(display, InputTiming.DEFAULT, warp);
	}

	public XTestBackend(Pointer display, InputTiming timing, PointerWarp warp) {
		this.display = display;
		this.timing = timing == null ? InputTiming.DEFAULT : timing;
		this.warp = warp == null ? PointerWarp.ROOT_ABSOLUTE : warp;
	}

	@Override
	public String name() {
		return "xtest";
	}

	@Override
	public boolean preservesCursor() {
		return false;
	}

	@Override
	public void clickWindow(Pointer window, int relX, int relY, int button) {
		Rectangle rect = X11Utils.getWindowGeometry(display, window);
		if (rect == null) {
			Diag.error("[Linux/xtest] Could not get window geometry for click.");
			return;
		}
		clickScreen(rect.x + relX, rect.y + relY, button);
	}

	@Override
	public void clickScreen(int xAbs, int yAbs, int button) {
		move(xAbs, yAbs);
		// A round-trip (not just XFlush) so the motion is applied server-side before the settle; a press read
		// in the same frame as the move is otherwise sampled at the old position.
		X11.INSTANCE.XSync(display, false);
		sleep(timing.motionSettleMs());
		button(button, true);
		sleep(timing.pressHoldMs());
		button(button, false);
	}

	@Override
	public void move(int xAbs, int yAbs) {
		Point origin = warpOrigin();
		XTest.INSTANCE.XTestFakeMotionEvent(display, -1, xAbs - origin.x, yAbs - origin.y, 0);
		X11.INSTANCE.XFlush(display);
	}

	/**
	 * The correction to apply to a root-absolute target before injecting it. Zero on a normal X server; on a
	 * {@link PointerWarp#FOCUS_RELATIVE} one (gamescope) it is the focused window's root origin, read live on
	 * every move — focus can change under us, the read is one round trip, and every click already pays an
	 * {@code XSync} plus a settle sleep, so this is not on any hot path worth caching.
	 */
	private Point warpOrigin() {
		if (warp != PointerWarp.FOCUS_RELATIVE) {
			return noCorrection();
		}
		try {
			PointerByReference focus = new PointerByReference();
			IntByReference revertTo = new IntByReference();
			X11.INSTANCE.XGetInputFocus(display, focus, revertTo);
			Pointer focused = focus.getValue();
			// None (0) / PointerRoot (1) are not windows — nothing to be relative to.
			if (focused == null || Pointer.nativeValue(focused) <= 1) {
				return noCorrection();
			}
			Rectangle geometry = X11Utils.getWindowGeometry(display, focused);
			return geometry == null ? noCorrection() : new Point(geometry.x, geometry.y);
		} catch (Throwable t) {
			// A failed read must degrade to the uncorrected warp, never to no motion at all.
			return noCorrection();
		}
	}

	private static Point noCorrection() {
		return new Point(0, 0);
	}

	@Override
	public boolean moveRelative(int dx, int dy) {
		// True device-relative motion — no read-back-then-warp, so it works even while a game holds a pointer
		// grab and warps the cursor to screen-centre every frame (mouselook). The delta is applied as-is.
		XTest.INSTANCE.XTestFakeRelativeMotionEvent(display, -1, dx, dy, 0);
		X11.INSTANCE.XFlush(display);
		return true;
	}

	@Override
	public void button(int button, boolean press) {
		XTest.INSTANCE.XTestFakeButtonEvent(display, button, press, 0);
		if (press) {
			heldButtons.add(button);
		} else {
			heldButtons.remove(button);
		}
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void key(int keysym, boolean press) {
		int keycode = X11.INSTANCE.XKeysymToKeycode(display, keysym) & 0xFF;
		boolean borrowed = false;
		if (keycode == 0) {
			// The active layout binds no keycode to this keysym — borrow a spare so the character is deliverable.
			keycode = keymap().rebind(keysym);
			borrowed = true;
			if (keycode == 0) {
				Diag.error("[Linux/xtest] no keycode and no spare to bind keysym 0x"
					+ Integer.toHexString(keysym) + "; character dropped");
				return;
			}
		}
		XTest.INSTANCE.XTestFakeKeyEvent(display, keycode, press, 0);
		X11.INSTANCE.XSync(display, false);
		if (press) {
			heldKeys.add(keycode);
		} else {
			heldKeys.remove(keycode);
			// Restore a borrowed keycode only after the release, so press and release used the same binding.
			if (borrowed && keymap != null) {
				keymap.restore(keysym);
			}
		}
	}

	@Override
	public void releaseHeld() {
		for (int keycode : heldKeys) {
			try {
				XTest.INSTANCE.XTestFakeKeyEvent(display, keycode, false, 0);
			} catch (Throwable ignored) {
				// best-effort — keep releasing the rest
			}
		}
		heldKeys.clear();
		for (int button : heldButtons) {
			try {
				XTest.INSTANCE.XTestFakeButtonEvent(display, button, false, 0);
			} catch (Throwable ignored) {
				// best-effort
			}
		}
		heldButtons.clear();
		X11.INSTANCE.XFlush(display);
		if (keymap != null) {
			keymap.restoreAll();
		}
	}

	@Override
	public int interKeyDelayMs() {
		return timing.interKeyMs();
	}

	@Override
	public void scroll(int amount) {
		if (amount == 0) {
			return;
		}
		int button = amount > 0 ? XTest.Button4 : XTest.Button5; // 4 = up/away, 5 = down/toward
		int ticks = Math.abs(amount);
		for (int i = 0; i < ticks; i++) {
			XTest.INSTANCE.XTestFakeButtonEvent(display, button, true, 0);
			XTest.INSTANCE.XTestFakeButtonEvent(display, button, false, 0);
		}
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void close() {
		// Never leave a key/button held or the user's layout altered because a session tore down mid-sequence.
		releaseHeld();
	}

	private Keymap keymap() {
		if (keymap == null) {
			keymap = new Keymap(new XlibKeymapOps(display));
		}
		return keymap;
	}

	private static void sleep(long ms) {
		if (ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
