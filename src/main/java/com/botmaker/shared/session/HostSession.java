package com.botmaker.shared.session;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.NativeControllerFactory;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.Launcher;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.Set;

/**
 * The {@link DesktopSession} over the user's real desktop — a thin wrapper around the default
 * {@link NativeController} that changes <b>nothing</b> about today's behaviour. It exists so bot code can be
 * written against {@link DesktopSession} now, and swap in a nested {@code :N} session (Phase 2) later without
 * touching a call site.
 *
 * <p>Its capabilities are deliberately honest about what a shared desktop can and can't do: it can attach,
 * launch, move the pointer (absolute and relative) and capture — but it advertises neither
 * {@link Capability#BACKGROUND_CLICK} nor {@link Capability#ISOLATED_FOCUS}, because on {@code :0} a click that
 * a game actually accepts has to move the real cursor and take focus. A bot that needs those checks
 * {@code has(...)} and refuses to run here rather than silently clicking into the void.
 *
 * <p><b>Ownership:</b> a host session wraps a controller it does not own (the process-wide singleton, shared
 * with bot runs), so {@link #close()} never closes it — closing the shared X11 connection would break every
 * other consumer.
 */
public final class HostSession implements DesktopSession {

	private final NativeController controller;
	private final HostPointer pointer = new HostPointer();
	private final HostKeyboard keyboard = new HostKeyboard();
	private volatile GenericWindow attached;
	private volatile boolean closed;

	/** Wraps {@code controller} without taking ownership of it (see the class note on {@link #close()}). */
	public HostSession(NativeController controller) {
		this.controller = controller;
	}

	/** A host session over the process-wide default controller ({@link NativeControllerFactory#get()}). */
	public static HostSession ofDefault() {
		return new HostSession(NativeControllerFactory.get());
	}

	@Override
	public Set<Capability> capabilities() {
		// No BACKGROUND_CLICK / ISOLATED_FOCUS / MULTI_SESSION: a shared desktop can't offer them (see class doc).
		return EnumSet.of(
			Capability.ABSOLUTE_POINTER,
			Capability.RELATIVE_POINTER,
			Capability.SCREEN_CAPTURE,
			Capability.WINDOW_ATTACH,
			Capability.WINDOW_LAUNCH);
	}

	@Override
	public Rectangle screen() {
		try {
			java.awt.Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
			return new Rectangle(0, 0, d.width, d.height);
		} catch (Throwable t) {
			// Headless or no display — the caller treats a zero rectangle as "unknown".
			return new Rectangle(0, 0, 0, 0);
		}
	}

	@Override
	public SessionPointer pointer() {
		return pointer;
	}

	@Override
	public SessionKeyboard keyboard() {
		return keyboard;
	}

	@Override
	public void attach(GenericWindow window) {
		this.attached = window;
	}

	@Override
	public GenericWindow attached() {
		return attached;
	}

	@Override
	public void launch(LaunchSpec spec) {
		// The host launch path exactly as it is today: onto the user's desktop, no display retargeting.
		Launcher.start(spec);
	}

	@Override
	public BufferedImage capture() {
		GenericWindow target = attached;
		return target == null ? null : controller.captureWindow(target);
	}

	@Override
	public NativeController controller() {
		return controller;
	}

	@Override
	public void close() {
		// Intentionally does NOT close the wrapped controller — it is shared (see the class note).
		closed = true;
	}

	/** True once {@link #close()} has been called — for tests/asserts; the controller stays open regardless. */
	public boolean isClosed() {
		return closed;
	}

	/** Pointer facade: raw device motion through the wrapped controller, routing relative moves via the read-back position. */
	private final class HostPointer implements SessionPointer {
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

	/** Keyboard facade: targeted at the {@link #attached() attached} window when there is one, else the focused window. */
	private final class HostKeyboard implements SessionKeyboard {
		@Override
		public void keyDown(int nativeKeyCode) {
			GenericWindow w = attached;
			if (w != null) controller.keyDown(w, nativeKeyCode); else controller.keyDown(nativeKeyCode);
		}

		@Override
		public void keyUp(int nativeKeyCode) {
			GenericWindow w = attached;
			if (w != null) controller.keyUp(w, nativeKeyCode); else controller.keyUp(nativeKeyCode);
		}

		@Override
		public void type(String text) {
			GenericWindow w = attached;
			if (w != null) controller.typeText(w, text); else controller.typeText(text);
		}
	}
}
