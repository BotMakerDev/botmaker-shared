package com.botmaker.shared.capture;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Cross-platform native window plumbing shared by the SDK (runtime) and Studio (editor).
 *
 * <p>Per-window capture ({@link #captureWindow}) returns {@code null} when it cannot produce a usable
 * frame (e.g. native Wayland, invalid geometry); callers supply their own full-desktop fallback. There
 * is deliberately no {@code captureDesktop()} here — the full-desktop capture engine lives in each
 * consumer (the SDK's {@code CaptureBackend}, the Studio's Robot/CLI grab).
 */
public interface NativeController {
	GenericWindow getForegroundWindow();
	List<GenericWindow> getChildWindows(GenericWindow parent);
	List<GenericWindow> getAllWindows();

	/**
	 * Enumerate windows, optionally including currently-minimized (unmapped) ones — which {@link #getAllWindows()}
	 * omits because their pixels aren't directly capturable. Callers use this to locate a minimized target and
	 * {@link #restoreWindow(GenericWindow) restore} it. Default: same as {@link #getAllWindows()} (additive).
	 */
	default List<GenericWindow> getAllWindows(boolean includeMinimized) {
		return getAllWindows();
	}

	/**
	 * Un-minimize / restore {@code window} so it becomes visible and its pixels capturable again. Intrusive by
	 * nature (the window returns to the foreground), mirroring the platform's normal restore. Default no-op so
	 * this stays additive for existing implementations.
	 */
	default void restoreWindow(GenericWindow window) {
	}

	/** Capture just this window's pixels, or {@code null} if a usable frame can't be produced. */
	BufferedImage captureWindow(GenericWindow window);

	/**
	 * Force the window whose title equals {@code windowTitle} to stack <em>above fullscreen</em> windows.
	 * Studio's transparent overlays are only {@code setAlwaysOnTop} (EWMH {@code _NET_WM_STATE_ABOVE}), which
	 * a fullscreen game still covers; this promotes the overlay via notification window-type + raise so it
	 * stays visible. Best-effort and additive: default no-op; only the X11 backend implements it (borderless
	 * always-on-top already wins on Windows).
	 */
	default void promoteOverlayAboveFullscreen(String windowTitle) {
	}

	void postLeftClick(GenericWindow window, int relativeX, int relativeY);
	void postLeftClickScreen(int xAbs, int yAbs);

	/**
	 * True if input synthesis leaves the user's real cursor untouched and can drive an unfocused/background
	 * window. On Linux this reflects the selected input backend (cursor-preserving XSendEvent vs.
	 * cursor-moving uinput/XTest); the default {@code false} keeps this additive for existing implementations.
	 */
	default boolean supportsBackgroundInput() {
		return false;
	}

	/**
	 * Ask this controller to switch to an input path that actually reaches the target, giving up the
	 * cursor-preserving guarantee if it must. Meant for <em>interactive</em> consumers (the pilot's Interact
	 * mode), not for bots: the cursor-safe default cannot drive Wine/Proton games or native Wayland clients,
	 * which silently drop the synthetic events it sends.
	 *
	 * <p>Default {@code true} — Windows already posts into the target's message queue, which is both reliable
	 * and cursor-safe, so there is nothing to escalate. Only the Linux backend overrides this. After a call
	 * that returns true, {@link #supportsBackgroundInput()} may flip to {@code false}: escalating is exactly
	 * the trade of "leaves your cursor alone" for "the click lands". The switch is <b>process-wide and
	 * sticky</b> — the controller is shared with bot runs.
	 *
	 * @return true if input is now on a reliable path, false if no better backend could be obtained
	 */
	default boolean useReliableInput() {
		return true;
	}

	// --- Window management ---
	void focusWindow(GenericWindow window);
	void moveWindow(GenericWindow window, int x, int y);
	void resizeWindow(GenericWindow window, int width, int height);

	// --- Input synthesis ---
	// keyDown/keyUp take a per-OS native key code (X keysym on Linux, virtual-key code on Windows);
	// callers resolve it from api.interaction.Key so the public API stays platform-neutral.
	void keyDown(int nativeKeyCode);
	void keyUp(int nativeKeyCode);
	void typeText(String text);

	/**
	 * Targeted key synthesis: deliver the key to {@code window} specifically rather than to whatever
	 * currently holds focus — the keyboard counterpart of {@link #postLeftClick(GenericWindow, int, int)}.
	 * Windows posts {@code WM_KEYDOWN/UP}/{@code WM_CHAR} straight to the HWND; the Linux xsendevent backend
	 * sends the synthetic {@code Key*} events to that window's client, so both inherit their click path's
	 * "no focus stolen, works in the background" property (and the same caveat — raw-input/DirectInput games
	 * ignore posted/synthetic events, exactly as they ignore the posted clicks). Default to the window-less
	 * path so the change is additive; a {@code null} window also falls back to the global path.
	 */
	default void keyDown(GenericWindow window, int nativeKeyCode) {
		keyDown(nativeKeyCode);
	}

	default void keyUp(GenericWindow window, int nativeKeyCode) {
		keyUp(nativeKeyCode);
	}

	default void typeText(GenericWindow window, String text) {
		typeText(text);
	}
	void mouseMove(int xAbs, int yAbs);
	void mouseButton(int button, boolean press); // 1=left, 2=middle, 3=right
	void scroll(int amount);                      // + = up/away, - = down/toward

	/**
	 * The real pointer's current absolute screen position, or {@code null} if it can't be read. Callers must
	 * treat {@code null} as "don't restore" rather than as an origin — a stale or invented coordinate would
	 * park the cursor somewhere the user never left it.
	 */
	default Point cursorPosition() {
		return null;
	}

	/**
	 * Click at an absolute screen coordinate on the <em>reliable</em> path (real pointer input, which games
	 * accept), leaving the cursor where it started.
	 *
	 * <p>This lives here rather than in the SDK's {@code Mouse} because both consumers need the identical
	 * policy and Studio does not depend on the SDK: the SDK's {@code Mouse.click} and Studio's
	 * {@code PilotInputService} {@code TAP} would otherwise each rebuild it and drift.
	 *
	 * <p>The default implementation is the portable one — read cursor, move, settle, press, release, move
	 * back. The settle delay matters: games sample the pointer on their own timer, so a press issued in the
	 * same instant as the move is read at the <em>old</em> position. A backend that can do this atomically
	 * (the xdotool one chains {@code mousemove … click … mousemove restore} in a single invocation) overrides
	 * it.
	 *
	 * <p>Note this deliberately cannot target an occluded window: real pointer input lands on whatever is
	 * topmost at that coordinate. Raising the target first is the caller's decision, since it is visible to
	 * the user.
	 */
	default void clickRestoringCursor(int xAbs, int yAbs, int button) {
		Point origin = cursorPosition();
		mouseMove(xAbs, yAbs);
		try {
			Thread.sleep(CLICK_SETTLE_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		mouseButton(button, true);
		mouseButton(button, false);
		if (origin != null) {
			mouseMove(origin.x, origin.y);
		}
	}

	/** Delay between positioning the pointer and pressing, so the target reads the new position. */
	int CLICK_SETTLE_MS = 20;
}
