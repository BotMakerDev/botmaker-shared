package com.botmaker.shared.capture.windows;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.Collectors;

public class WindowsController implements NativeController {

	@Override
	public GenericWindow getForegroundWindow() {
		HWND hwnd = User32.INSTANCE.GetForegroundWindow();
		return toGenericWindow(hwnd);
	}

	@Override
	public List<GenericWindow> getChildWindows(GenericWindow parent) {
		HWND parentHwnd = (HWND) parent.getNativeHandle();
		return WindowFinder.getChildWindows(parentHwnd).stream()
			.map(info -> toGenericWindow(info.getHWnd()))
			.collect(Collectors.toList());
	}

	@Override
	public List<GenericWindow> getAllWindows() {
		return WindowFinder.getAllWindows().stream()
			.map(info -> toGenericWindow(info.getHWnd()))
			.collect(Collectors.toList());
	}

	@Override
	public BufferedImage captureWindow(GenericWindow window) {
		return WindowCapture.capture((HWND) window.getNativeHandle());
	}

	@Override
	public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
		if (reliableInput) {
			// Real input hits whatever is topmost, so raise the target first (same rule as the Linux
			// xdotool path). Then click in absolute coordinates with the cursor put back afterwards.
			HWND hwnd = (HWND) window.getNativeHandle();
			focusWindow(window);
			POINT pt = new POINT();
			pt.x = relativeX;
			pt.y = relativeY;
			User32.INSTANCE.ClientToScreen(hwnd, pt);
			clickRestoringCursor(pt.x, pt.y, 1);
			return;
		}
		Clicker.postLeftClick((HWND) window.getNativeHandle(), relativeX, relativeY);
	}

	/**
	 * The posted-message path can only express a left click, so anything else — and everything once
	 * {@link #useReliableInput()} has escalated — goes to the real-device default.
	 */
	@Override
	public void click(int xAbs, int yAbs, int button) {
		if (reliableInput || button != 1) {
			NativeController.super.click(xAbs, yAbs, button);
			return;
		}
		Clicker.postLeftClickScreen(xAbs, yAbs);
	}

	/** The pointer's absolute screen position, or {@code null} if {@code GetCursorPos} fails. */
	@Override
	public java.awt.Point cursorPosition() {
		POINT pt = new POINT();
		return User32.INSTANCE.GetCursorPos(pt) ? new java.awt.Point(pt.x, pt.y) : null;
	}

	/**
	 * True only while on the posted-message path, which genuinely does drive a background window without
	 * touching the cursor. Once {@link #useReliableInput()} has escalated, input goes through the real device
	 * and this correctly reports false.
	 */
	@Override
	public boolean supportsBackgroundInput() {
		return !reliableInput;
	}

	/**
	 * Switch to real device input ({@code SetCursorPos} + {@code mouse_event} / scancode {@code keybd_event})
	 * instead of {@code PostMessage}.
	 *
	 * <p>This used to be the inherited no-op returning true, on the claim that posting to a window's message
	 * queue is "both reliable and cursor-safe". The second half holds; the first does not — Wine/Proton and
	 * DirectInput games read raw input and never look at their message queue, so every posted click was
	 * silently dropped. Escalating trades background operation for the click actually landing.
	 *
	 * <p>Idempotent and process-wide, matching the Linux backend swap.
	 */
	@Override
	public boolean useReliableInput() {
		reliableInput = true;
		return true;
	}

	@Override
	public void focusWindow(GenericWindow window) {
		HWND hwnd = (HWND) window.getNativeHandle();
		User32.INSTANCE.ShowWindow(hwnd, User32.SW_RESTORE);
		User32.INSTANCE.SetForegroundWindow(hwnd);
	}

	@Override
	public void restoreWindow(GenericWindow window) {
		if (window == null) return;
		HWND hwnd = (HWND) window.getNativeHandle();
		User32.INSTANCE.ShowWindow(hwnd, User32.SW_RESTORE);
		User32.INSTANCE.SetForegroundWindow(hwnd);
	}

	@Override
	public void moveWindow(GenericWindow window, int x, int y) {
		HWND hwnd = (HWND) window.getNativeHandle();
		User32.INSTANCE.SetWindowPos(hwnd, null, x, y, 0, 0,
			User32.SWP_NOSIZE | User32.SWP_NOZORDER | User32.SWP_NOACTIVATE);
	}

	@Override
	public void resizeWindow(GenericWindow window, int width, int height) {
		HWND hwnd = (HWND) window.getNativeHandle();
		User32.INSTANCE.SetWindowPos(hwnd, null, 0, 0, width, height,
			User32.SWP_NOMOVE | User32.SWP_NOZORDER | User32.SWP_NOACTIVATE);
	}

	// --- Input synthesis (keybd_event / mouse_event; struct-free, no SendInput plumbing) ---

	private static final int VK_SHIFT = 0x10;

	/**
	 * Whether input has been escalated to the real-device path. Sticky and process-wide, matching the Linux
	 * backend swap; see {@link #useReliableInput()}.
	 */
	private volatile boolean reliableInput = false;

	// Keystrokes always carry a scancode. keybd_event with bScan=0 (what this used to send) is invisible to
	// DirectInput/RawInput games, which read scancodes rather than virtual keys — that, and not only the
	// PostMessage path, is why keyboard input never reached a game.
	private static void sendKey(int nativeKeyCode, boolean press) {
		int scan = User32.INSTANCE.MapVirtualKeyA(nativeKeyCode, User32.MAPVK_VK_TO_VSC);
		int flags = (press ? 0 : User32.KEYEVENTF_KEYUP)
			| (scan != 0 ? User32.KEYEVENTF_SCANCODE : 0);
		User32.INSTANCE.keybd_event((byte) nativeKeyCode, (byte) scan, flags, null);
	}

	@Override
	public void keyDown(int nativeKeyCode) {
		sendKey(nativeKeyCode, true);
	}

	@Override
	public void keyUp(int nativeKeyCode) {
		sendKey(nativeKeyCode, false);
	}

	@Override
	public void typeText(String text) {
		if (text == null) return;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			short scan = User32.INSTANCE.VkKeyScanA((byte) c);
			int vk = scan & 0xFF;
			boolean needShift = (scan & 0x100) != 0;
			if (needShift) keyDown(VK_SHIFT);
			keyDown(vk);
			keyUp(vk);
			if (needShift) keyUp(VK_SHIFT);
		}
	}

	// --- Targeted key synthesis: PostMessage straight to a specific HWND (background, focus-preserving) ---
	// lParam encodes the keystroke context: bit 0 is the repeat count (1); on key-up bits 30/31 (previous
	// key state + transition) are set, which well-behaved windows expect on a WM_KEYUP.
	private static final long KEYDOWN_LPARAM = 0x00000001L;
	private static final long KEYUP_LPARAM = 0xC0000001L;

	@Override
	public void keyDown(GenericWindow window, int nativeKeyCode) {
		if (window == null || reliableInput) {
			// Escalated: focus the target, then drive the real keyboard — a posted WM_KEYDOWN is exactly
			// what a raw-input game ignores.
			if (window != null) focusWindow(window);
			keyDown(nativeKeyCode);
			return;
		}
		User32.INSTANCE.PostMessage((HWND) window.getNativeHandle(), User32.WM_KEYDOWN,
			new WPARAM(nativeKeyCode), new LPARAM(KEYDOWN_LPARAM));
	}

	@Override
	public void keyUp(GenericWindow window, int nativeKeyCode) {
		if (window == null || reliableInput) {
			keyUp(nativeKeyCode);
			return;
		}
		User32.INSTANCE.PostMessage((HWND) window.getNativeHandle(), User32.WM_KEYUP,
			new WPARAM(nativeKeyCode), new LPARAM(KEYUP_LPARAM));
	}

	@Override
	public void typeText(GenericWindow window, String text) {
		if (text == null) return;
		if (window == null || reliableInput) {
			if (window != null) focusWindow(window);
			typeText(text);
			return;
		}
		// WM_CHAR carries the character directly, so shifting/layout is the target's concern, not ours.
		HWND hwnd = (HWND) window.getNativeHandle();
		for (int i = 0; i < text.length(); i++) {
			User32.INSTANCE.PostMessage(hwnd, User32.WM_CHAR,
				new WPARAM(text.charAt(i)), new LPARAM(KEYDOWN_LPARAM));
		}
	}

	@Override
	public void mouseMove(int xAbs, int yAbs) {
		User32.INSTANCE.SetCursorPos(xAbs, yAbs);
	}

	@Override
	public void mouseButton(int button, boolean press) {
		int flag = switch (button) {
			case 2 -> press ? User32.MOUSEEVENTF_MIDDLEDOWN : User32.MOUSEEVENTF_MIDDLEUP;
			case 3 -> press ? User32.MOUSEEVENTF_RIGHTDOWN : User32.MOUSEEVENTF_RIGHTUP;
			default -> press ? User32.MOUSEEVENTF_LEFTDOWN : User32.MOUSEEVENTF_LEFTUP;
		};
		User32.INSTANCE.mouse_event(flag, 0, 0, 0, null);
	}

	@Override
	public void scroll(int amount) {
		// One wheel notch = WHEEL_DELTA (120); positive scrolls up/away.
		User32.INSTANCE.mouse_event(User32.MOUSEEVENTF_WHEEL, 0, 0, amount * 120, null);
	}

	// --- Helper to convert Windows HWND to GenericWindow ---
	private GenericWindow toGenericWindow(HWND hwnd) {
		if (hwnd == null) return null;

		byte[] windowText = new byte[512];
		User32.INSTANCE.GetWindowTextA(hwnd.getPointer(), windowText, 512);
		String title = new String(windowText).trim();

		RECT winRect = new RECT();
		User32.INSTANCE.GetWindowRect(hwnd.getPointer(), winRect);
		Rectangle rect = new Rectangle(winRect.left, winRect.top,
			winRect.right - winRect.left,
			winRect.bottom - winRect.top);

		return new GenericWindow(hwnd, title, rect);
	}
}
