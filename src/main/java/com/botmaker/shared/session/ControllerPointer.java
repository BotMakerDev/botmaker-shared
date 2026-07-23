package com.botmaker.shared.session;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.NativeController;

import java.awt.Point;

/**
 * A {@link SessionPointer} backed by a {@link NativeController} — the one pointer implementation both a
 * {@link HostSession} (over {@code :0}) and a {@code NestedSession} (over {@code :N}) use. What differs between
 * the two is only <em>which</em> controller it wraps: on {@code :N} the controller's global cursor is the bot's
 * alone, so the very same device-level motion that is intrusive on {@code :0} is flawless in the background.
 *
 * <p>{@link #moveRelative} anchors on the read-back position and skips (rather than warping to the bare delta)
 * when the position is unreadable — Phase 4 replaces this with true relative injection for grab/mouselook.
 */
final class ControllerPointer implements SessionPointer {

	private final NativeController controller;

	ControllerPointer(NativeController controller) {
		this.controller = controller;
	}

	@Override
	public void moveAbsolute(int x, int y) {
		controller.mouseMove(x, y);
	}

	@Override
	public void moveRelative(int dx, int dy) {
		Point p = controller.cursorPosition();
		if (p == null) {
			// Can't read where the pointer is, so a delta has no anchor — skip rather than warp to (dx,dy).
			Diag.log("[Session] moveRelative: pointer position unreadable; delta (" + dx + "," + dy + ") skipped");
			return;
		}
		controller.mouseMove(p.x + dx, p.y + dy);
	}

	@Override
	public void button(int button, boolean press) {
		controller.mouseButton(button, press);
	}

	@Override
	public void scroll(int amount) {
		controller.scroll(amount);
	}

	@Override
	public Point position() {
		return controller.cursorPosition();
	}
}
