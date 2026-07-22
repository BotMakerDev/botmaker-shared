package com.botmaker.shared.capture.linux.input;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.linux.X11Utils;
import com.sun.jna.Pointer;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure-Java uinput backend — creates a virtual absolute pointer + keyboard via {@code /dev/uinput} and drives
 * it below the compositor. This is the <b>reliable-everywhere</b> path: it reaches every app including native
 * Wayland and games (SDL/Chromium), unlike {@link XSendEventBackend}.
 *
 * <p><b>It moves the one shared cursor</b> and cannot restore it (Wayland won't report the prior position), so
 * it is an explicit opt-in ({@code botmaker.linux.input=uinput}), not the default. Requires write access to
 * {@code /dev/uinput} (a udev/logind ACL grants it to the seat user — no root, no permission dialog).
 *
 * <p>Absolute positioning maps the device across a single output; on multi-monitor layouts clicks may land on
 * the primary output only.
 */
public final class UinputBackend implements LinuxInputBackend {

	// evdev KEY_* scancodes (linux/input-event-codes.h) — NOT alphabetical.
	private static final int KEY_ESC = 1, KEY_BACKSPACE = 14, KEY_TAB = 15, KEY_ENTER = 28, KEY_LEFTSHIFT = 42,
		KEY_RIGHTSHIFT = 54, KEY_SPACE = 57, KEY_MINUS = 12, KEY_DOT = 52, KEY_COMMA = 51;

	/** keysym (X) → evdev KEY_* code. Letters map by identity of the letter (case handled via Shift keysym). */
	private static final Map<Integer, Integer> KEYSYM_TO_KEY = buildKeymap();

	private final int fd;
	private final int screenW;
	private final int screenH;
	private final Pointer display; // only used to convert window-relative clicks to screen coordinates

	private UinputBackend(int fd, int screenW, int screenH, Pointer display) {
		this.fd = fd;
		this.screenW = Math.max(1, screenW);
		this.screenH = Math.max(1, screenH);
		this.display = display;
	}

	/**
	 * Open {@code /dev/uinput} and register a virtual mouse+keyboard, or return {@code null} if the device
	 * can't be opened/created (missing permission, no uinput). The caller then falls back to a cursor-safe
	 * backend and logs. {@code display} is used only to resolve window-relative click coordinates to screen.
	 */
	public static UinputBackend tryCreate(int screenW, int screenH, Pointer display) {
		int fd = CLib.INSTANCE.open("/dev/uinput", CLib.O_WRONLY | CLib.O_NONBLOCK);
		if (fd < 0) {
			return null;
		}
		try {
			// Capabilities: keys/buttons, relative wheel, absolute X/Y, sync.
			ioctlInt(fd, CLib.UI_SET_EVBIT(), CLib.EV_KEY);
			ioctlInt(fd, CLib.UI_SET_EVBIT(), CLib.EV_REL);
			ioctlInt(fd, CLib.UI_SET_EVBIT(), CLib.EV_ABS);
			ioctlInt(fd, CLib.UI_SET_EVBIT(), CLib.EV_SYN);

			for (int btn : new int[]{CLib.BTN_LEFT, CLib.BTN_RIGHT, CLib.BTN_MIDDLE}) {
				ioctlInt(fd, CLib.UI_SET_KEYBIT(), btn);
			}
			// Register only the KEY_* codes we actually emit — a tight capability set keeps libinput from
			// misclassifying the device (a broad keyboard range + ABS axes reads as an ambiguous kbd/joystick).
			for (int key : new java.util.HashSet<>(KEYSYM_TO_KEY.values())) {
				ioctlInt(fd, CLib.UI_SET_KEYBIT(), key);
			}
			ioctlInt(fd, CLib.UI_SET_RELBIT(), CLib.REL_WHEEL);
			ioctlInt(fd, CLib.UI_SET_ABSBIT(), CLib.ABS_X);
			ioctlInt(fd, CLib.UI_SET_ABSBIT(), CLib.ABS_Y);
			// Mark the absolute axes as a pointer so the compositor moves the cursor to (ABS_X, ABS_Y).
			ioctlInt(fd, CLib.UI_SET_PROPBIT(), CLib.INPUT_PROP_POINTER);

			CLib.UinputSetup setup = new CLib.UinputSetup();
			setup.bustype = (short) CLib.BUS_USB;
			setup.vendor = 0x6274;  // "bt"
			setup.product = 0x6d6b; // "mk"
			setup.version = 1;
			byte[] name = "botmaker-virtual-input".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
			System.arraycopy(name, 0, setup.name, 0, Math.min(name.length, setup.name.length - 1));
			setup.write();
			CLib.INSTANCE.ioctl(fd, CLib.UI_DEV_SETUP(setup.size()), setup.getPointer());

			absSetup(fd, CLib.ABS_X, Math.max(1, screenW - 1));
			absSetup(fd, CLib.ABS_Y, Math.max(1, screenH - 1));

			int rc = CLib.INSTANCE.ioctl(fd, CLib.UI_DEV_CREATE(), 0);
			if (rc < 0) {
				CLib.INSTANCE.close(fd);
				return null;
			}
			sleep(120); // let udev create the node and the compositor attach the device
			return new UinputBackend(fd, screenW, screenH, display);
		} catch (Throwable t) {
			Diag.error("[Linux/uinput] Failed to create virtual device: " + t.getMessage());
			try {
				CLib.INSTANCE.close(fd);
			} catch (Throwable ignored) {
				// best effort
			}
			return null;
		}
	}

	@Override
	public String name() {
		return "uinput";
	}

	@Override
	public boolean preservesCursor() {
		return false;
	}

	@Override
	public void clickWindow(Pointer window, int relX, int relY, int button) {
		// uinput has no window concept — convert window-relative to absolute screen coordinates and click there.
		Rectangle rect = X11Utils.getWindowGeometry(display, window);
		if (rect == null) {
			Diag.error("[Linux/uinput] Could not get window geometry for click.");
			return;
		}
		clickScreen(rect.x + relX, rect.y + relY, button);
	}

	@Override
	public void clickScreen(int xAbs, int yAbs, int button) {
		move(xAbs, yAbs);
		button(button, true);
		syn();
		sleep(10);
		button(button, false);
		syn();
	}

	@Override
	public void move(int xAbs, int yAbs) {
		emit(CLib.EV_ABS, CLib.ABS_X, clamp(xAbs, screenW - 1));
		emit(CLib.EV_ABS, CLib.ABS_Y, clamp(yAbs, screenH - 1));
		syn();
	}

	@Override
	public void button(int button, boolean press) {
		int code = evButton(button);
		if (code < 0) {
			return;
		}
		emit(CLib.EV_KEY, code, press ? 1 : 0);
		syn();
	}

	@Override
	public void key(int keysym, boolean press) {
		Integer code = KEYSYM_TO_KEY.get(keysym);
		if (code == null) {
			return;
		}
		emit(CLib.EV_KEY, code, press ? 1 : 0);
		syn();
	}

	@Override
	public void scroll(int amount) {
		if (amount == 0) {
			return;
		}
		int dir = amount > 0 ? 1 : -1; // + = up/away
		int ticks = Math.abs(amount);
		for (int i = 0; i < ticks; i++) {
			emit(CLib.EV_REL, CLib.REL_WHEEL, dir);
			syn();
		}
	}

	@Override
	public void close() {
		try {
			CLib.INSTANCE.ioctl(fd, CLib.UI_DEV_DESTROY(), 0);
		} catch (Throwable ignored) {
			// best effort
		}
		try {
			CLib.INSTANCE.close(fd);
		} catch (Throwable ignored) {
			// best effort
		}
	}

	// --- helpers ---

	private void emit(int type, int code, int value) {
		CLib.InputEvent ev = new CLib.InputEvent();
		ev.type = (short) type;
		ev.code = (short) code;
		ev.value = value;
		ev.write();
		CLib.INSTANCE.write(fd, ev.getPointer(), ev.size());
	}

	private void syn() {
		emit(CLib.EV_SYN, CLib.SYN_REPORT, 0);
	}

	private static int evButton(int xButton) {
		switch (xButton) {
			case 1: return CLib.BTN_LEFT;
			case 2: return CLib.BTN_MIDDLE;
			case 3: return CLib.BTN_RIGHT;
			default: return -1;
		}
	}

	private static void ioctlInt(int fd, com.sun.jna.NativeLong request, int arg) {
		CLib.INSTANCE.ioctl(fd, request, arg);
	}

	private static void absSetup(int fd, int axis, int max) {
		CLib.UinputAbsSetup abs = new CLib.UinputAbsSetup();
		abs.code = (short) axis;
		abs.minimum = 0;
		abs.maximum = max;
		abs.write();
		CLib.INSTANCE.ioctl(fd, CLib.UI_ABS_SETUP(abs.size()), abs.getPointer());
	}

	private static int clamp(int v, int max) {
		return v < 0 ? 0 : Math.min(v, max);
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static Map<Integer, Integer> buildKeymap() {
		Map<Integer, Integer> m = new HashMap<>();
		// Letters: X keysym for 'a'..'z' == 0x61..0x7a, 'A'..'Z' == 0x41..0x5a. Both map to the same KEY_
		// (case is produced by the separately-injected Shift keysym).
		int[] letters = {
			// a  b  c  d  e  f  g  h  i  j  k  l  m  n  o  p  q  r  s  t  u  v  w  x  y  z
			30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44
		};
		for (int i = 0; i < 26; i++) {
			m.put('a' + i, letters[i]);
			m.put('A' + i, letters[i]);
		}
		// Digits: keysym '0'..'9' == 0x30..0x39. KEY_1..KEY_9 = 2..10, KEY_0 = 11.
		m.put((int) '1', 2);
		m.put((int) '2', 3);
		m.put((int) '3', 4);
		m.put((int) '4', 5);
		m.put((int) '5', 6);
		m.put((int) '6', 7);
		m.put((int) '7', 8);
		m.put((int) '8', 9);
		m.put((int) '9', 10);
		m.put((int) '0', 11);
		// Common printable/whitespace + a few control keysyms.
		m.put((int) ' ', KEY_SPACE);
		m.put((int) '-', KEY_MINUS);
		m.put((int) '.', KEY_DOT);
		m.put((int) ',', KEY_COMMA);
		m.put(0xFF0D, KEY_ENTER);     // Return
		m.put(0xFF8D, KEY_ENTER);     // KP_Enter
		m.put(0xFF09, KEY_TAB);       // Tab
		m.put(0xFF08, KEY_BACKSPACE); // BackSpace
		m.put(0xFF1B, KEY_ESC);       // Escape
		m.put(0xFFE1, KEY_LEFTSHIFT); // Shift_L
		m.put(0xFFE2, KEY_RIGHTSHIFT);// Shift_R
		return m;
	}
}
