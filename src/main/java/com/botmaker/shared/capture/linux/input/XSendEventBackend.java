package com.botmaker.shared.capture.linux.input;

import com.botmaker.shared.capture.linux.X11;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Cursor-preserving background input via {@code XSendEvent} — the default Linux backend.
 *
 * <p>Instead of warping the shared pointer (XTest), it delivers synthetic {@code ButtonPress}/{@code
 * ButtonRelease}/{@code MotionNotify}/{@code Key*} events straight to the target window. The real cursor is
 * never moved, and the target need not be focused or on top, so a bot runs in the background.
 *
 * <p><b>Scope & reliability.</b> This reaches only X11/XWayland windows — exactly the set this module can
 * enumerate and capture (native Wayland windows are invisible to X and already unsupported here). Events sent
 * this way carry {@code send_event=True}; toolkits that reject synthetic input (SDL/most games,
 * Chromium/Electron, parts of GTK4) will ignore them — for those, users opt into {@link UinputBackend}, which
 * is reliable everywhere but moves the cursor.
 *
 * <p>The backend keeps a <b>virtual</b> pointer position (updated by {@link #move}) used by {@link #button}
 * and {@link #scroll}; it is entirely independent of the real cursor.
 */
public final class XSendEventBackend implements LinuxInputBackend {

	private static final int CLICK_DELAY_MS = 10;

	// Button state-mask bits (X.h) — reported in the release event's state field.
	private static final int[] BUTTON_MASK = {0, 1 << 8, 1 << 9, 1 << 10, 1 << 11, 1 << 12};

	private final Pointer display;
	private final Pointer root;

	// Virtual pointer position (absolute screen px). Never the real cursor; drives button()/scroll().
	private volatile int lastX = 0;
	private volatile int lastY = 0;

	public XSendEventBackend(Pointer display) {
		this.display = display;
		this.root = X11.INSTANCE.XDefaultRootWindow(display);
	}

	@Override
	public String name() {
		return "xsendevent";
	}

	@Override
	public boolean preservesCursor() {
		return true;
	}

	@Override
	public void clickWindow(Pointer window, int relX, int relY, int button) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return;
		}
		int[] rootXY = translate(window, root, relX, relY);
		Target t = drillDown(window, relX, relY);
		dispatchClick(t.window, t.x, t.y, rootXY[0], rootXY[1], button);
		lastX = rootXY[0];
		lastY = rootXY[1];
	}

	@Override
	public void clickScreen(int xAbs, int yAbs, int button) {
		Target t = drillDown(root, xAbs, yAbs);
		dispatchClick(t.window, t.x, t.y, xAbs, yAbs, button);
		lastX = xAbs;
		lastY = yAbs;
	}

	@Override
	public void move(int xAbs, int yAbs) {
		lastX = xAbs;
		lastY = yAbs;
		// Notify the hovered window so hover-driven UIs update, without touching the real cursor.
		Target t = drillDown(root, xAbs, yAbs);
		send(t.window, X11.MotionNotify, t.x, t.y, xAbs, yAbs, 0, 0, X11.PointerMotionMask);
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void button(int button, boolean press) {
		Target t = drillDown(root, lastX, lastY);
		int type = press ? X11.ButtonPress : X11.ButtonRelease;
		long mask = press ? X11.ButtonPressMask : X11.ButtonReleaseMask;
		int state = press ? 0 : buttonMask(button);
		send(t.window, type, t.x, t.y, lastX, lastY, state, button, mask);
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void key(int keysym, boolean press) {
		int keycode = X11.INSTANCE.XKeysymToKeycode(display, keysym) & 0xFF;
		if (keycode == 0) {
			return;
		}
		Pointer focus = focusedWindow();
		if (focus == null || Pointer.nativeValue(focus) == 0) {
			return;
		}
		int type = press ? X11.KeyPress : X11.KeyRelease;
		long mask = press ? X11.KeyPressMask : X11.KeyReleaseMask;
		// For key events the struct's trailing field carries the keycode (same layout as XButtonEvent).
		send(focus, type, 1, 1, lastX, lastY, 0, keycode, mask);
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void key(Pointer window, int keysym, boolean press) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			key(keysym, press); // no target → focused-window path
			return;
		}
		int keycode = X11.INSTANCE.XKeysymToKeycode(display, keysym) & 0xFF;
		if (keycode == 0) {
			return;
		}
		int type = press ? X11.KeyPress : X11.KeyRelease;
		long mask = press ? X11.KeyPressMask : X11.KeyReleaseMask;
		// Deliver straight to the target's client — no focus change, no cursor move (same as clickWindow).
		send(window, type, 1, 1, lastX, lastY, 0, keycode, mask);
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void scroll(int amount) {
		if (amount == 0) {
			return;
		}
		int button = amount > 0 ? 4 : 5; // 4 = up/away, 5 = down/toward
		int ticks = Math.abs(amount);
		Target t = drillDown(root, lastX, lastY);
		for (int i = 0; i < ticks; i++) {
			send(t.window, X11.ButtonPress, t.x, t.y, lastX, lastY, 0, button, X11.ButtonPressMask);
			send(t.window, X11.ButtonRelease, t.x, t.y, lastX, lastY, buttonMask(button), button,
				X11.ButtonReleaseMask);
		}
		X11.INSTANCE.XFlush(display);
	}

	// --- helpers ---

	/** A press+release pair (with a leading motion) delivered to a resolved leaf window. */
	private void dispatchClick(Pointer window, int winX, int winY, int rootX, int rootY, int button) {
		send(window, X11.MotionNotify, winX, winY, rootX, rootY, 0, 0, X11.PointerMotionMask);
		send(window, X11.ButtonPress, winX, winY, rootX, rootY, 0, button, X11.ButtonPressMask);
		X11.INSTANCE.XFlush(display);
		sleep(CLICK_DELAY_MS);
		send(window, X11.ButtonRelease, winX, winY, rootX, rootY, buttonMask(button), button,
			X11.ButtonReleaseMask);
		X11.INSTANCE.XFlush(display);
	}

	/** Fill and send one synthetic event to {@code window}. {@code detail} is the button or keycode. */
	private void send(Pointer window, int type, int winX, int winY, int rootX, int rootY,
					  int state, int detail, long eventMask) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return;
		}
		X11.XButtonEvent ev = new X11.XButtonEvent();
		ev.type = type;
		ev.serial = new NativeLong(0);
		ev.send_event = 1; // the server overrides this to True regardless
		ev.display = display;
		ev.window = new NativeLong(Pointer.nativeValue(window));
		ev.root = new NativeLong(Pointer.nativeValue(root));
		ev.subwindow = new NativeLong(0);
		ev.time = new NativeLong(X11.CurrentTime);
		ev.x = winX;
		ev.y = winY;
		ev.x_root = rootX;
		ev.y_root = rootY;
		ev.state = state;
		ev.button = detail;
		ev.same_screen = 1;
		// propagate=false + a matching mask delivers to the window's owning client.
		X11.INSTANCE.XSendEvent(display, window, false, new NativeLong(eventMask), ev);
	}

	/** Translate a point from {@code src}'s coordinate space to {@code dest}'s. Returns {dx, dy}. */
	private int[] translate(Pointer src, Pointer dest, int x, int y) {
		IntByReference dx = new IntByReference();
		IntByReference dy = new IntByReference();
		PointerByReference child = new PointerByReference();
		X11.INSTANCE.XTranslateCoordinates(display, src, dest, x, y, dx, dy, child);
		return new int[]{dx.getValue(), dy.getValue()};
	}

	/**
	 * Walk down the window tree from {@code start} to the deepest child containing the point, so events land
	 * on the actual content widget rather than a WM frame or top-level. {@code x,y} are relative to {@code
	 * start} (for the root window that is screen coordinates).
	 */
	private Target drillDown(Pointer start, int x, int y) {
		Pointer win = start;
		int cx = x;
		int cy = y;
		// Bound the descent so a pathological tree can't spin forever.
		for (int depth = 0; depth < 64; depth++) {
			IntByReference ox = new IntByReference();
			IntByReference oy = new IntByReference();
			PointerByReference childRef = new PointerByReference();
			int ok = X11.INSTANCE.XTranslateCoordinates(display, win, win, cx, cy, ox, oy, childRef);
			if (ok == 0) {
				break;
			}
			Pointer child = childRef.getValue();
			if (child == null || Pointer.nativeValue(child) == 0) {
				break;
			}
			int[] childXY = translate(win, child, cx, cy);
			win = child;
			cx = childXY[0];
			cy = childXY[1];
		}
		return new Target(win, cx, cy);
	}

	private Pointer focusedWindow() {
		PointerByReference focus = new PointerByReference();
		IntByReference revert = new IntByReference();
		X11.INSTANCE.XGetInputFocus(display, focus, revert);
		return focus.getValue();
	}

	private static int buttonMask(int button) {
		return (button >= 0 && button < BUTTON_MASK.length) ? BUTTON_MASK[button] : 0;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** A resolved target window plus the point in that window's coordinate space. */
	private static final class Target {
		final Pointer window;
		final int x;
		final int y;

		Target(Pointer window, int x, int y) {
			this.window = window;
			this.x = x;
			this.y = y;
		}
	}
}
