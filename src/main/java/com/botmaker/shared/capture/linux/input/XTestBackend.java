package com.botmaker.shared.capture.linux.input;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.linux.X11;
import com.botmaker.shared.capture.linux.X11Utils;
import com.botmaker.shared.capture.linux.XTest;
import com.sun.jna.Pointer;

import java.awt.Rectangle;

/**
 * XTest backend — the original behavior: {@code XTestFakeMotionEvent} warps the one shared cursor to the
 * target, then clicks. {@link #preservesCursor()} is false. Kept as an explicit opt-in
 * ({@code botmaker.linux.input=xtest}); it does <b>not</b> restore the cursor afterwards (on Wayland the prior
 * position can't be read — see {@link com.botmaker.shared.capture.linux.LinuxController}).
 */
public final class XTestBackend implements LinuxInputBackend {

	private final Pointer display;

	public XTestBackend(Pointer display) {
		this.display = display;
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
		sleep(10);
		button(button, true);
		sleep(10);
		button(button, false);
	}

	@Override
	public void move(int xAbs, int yAbs) {
		XTest.INSTANCE.XTestFakeMotionEvent(display, -1, xAbs, yAbs, 0);
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void button(int button, boolean press) {
		XTest.INSTANCE.XTestFakeButtonEvent(display, button, press, 0);
		X11.INSTANCE.XFlush(display);
	}

	@Override
	public void key(int keysym, boolean press) {
		int keycode = X11.INSTANCE.XKeysymToKeycode(display, keysym) & 0xFF;
		if (keycode == 0) {
			return;
		}
		XTest.INSTANCE.XTestFakeKeyEvent(display, keycode, press, 0);
		X11.INSTANCE.XFlush(display);
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

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
