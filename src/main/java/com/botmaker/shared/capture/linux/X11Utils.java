package com.botmaker.shared.capture.linux;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;

/**
 * Utility methods for working with X11
 */
public class X11Utils {

	/**
	 * Get window title using various X11 properties
	 */
	public static String getWindowTitle(Pointer display, Pointer window) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return "";
		}

		// Try _NET_WM_NAME first (UTF-8)
		String title = getWindowProperty(display, window, "_NET_WM_NAME", "UTF8_STRING");
		if (title != null && !title.isEmpty()) {
			return title;
		}

		// Try WM_NAME (legacy)
		title = getWindowProperty(display, window, "WM_NAME", "STRING");
		if (title != null && !title.isEmpty()) {
			return title;
		}

		// Fallback to XFetchName
		PointerByReference nameRef = new PointerByReference();
		if (X11.INSTANCE.XFetchName(display, window, nameRef) != 0) {
			Pointer namePtr = nameRef.getValue();
			if (namePtr != null) {
				String name = namePtr.getString(0);
				X11.INSTANCE.XFree(namePtr);
				return name != null ? name : "";
			}
		}

		return "";
	}

	/**
	 * Get a window property as a string
	 */
	public static String getWindowProperty(Pointer display, Pointer window, String propertyName, String typeName) {
		Pointer propertyAtom = X11.INSTANCE.XInternAtom(display, propertyName, false);
		Pointer typeAtom = typeName != null ? X11.INSTANCE.XInternAtom(display, typeName, false) : null;

		PointerByReference actualTypeReturn = new PointerByReference();
		IntByReference actualFormatReturn = new IntByReference();
		IntByReference nItemsReturn = new IntByReference();
		IntByReference bytesAfterReturn = new IntByReference();
		PointerByReference propReturn = new PointerByReference();

		int result = X11.INSTANCE.XGetWindowProperty(
			display, window, propertyAtom,
			0, 1024, false,
			typeAtom != null ? typeAtom : new Pointer(X11.AnyPropertyType),
			actualTypeReturn, actualFormatReturn,
			nItemsReturn, bytesAfterReturn, propReturn
		);

		if (result == X11.Success && nItemsReturn.getValue() > 0) {
			Pointer prop = propReturn.getValue();
			if (prop != null) {
				byte[] data = prop.getByteArray(0, nItemsReturn.getValue());
				X11.INSTANCE.XFree(prop);

				// Remove null terminators
				int length = data.length;
				while (length > 0 && data[length - 1] == 0) {
					length--;
				}

				return new String(data, 0, length, StandardCharsets.UTF_8).trim();
			}
		}

		return null;
	}

	/**
	 * Get window geometry
	 */
	public static Rectangle getWindowGeometry(Pointer display, Pointer window) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return null;
		}

		PointerByReference rootReturn = new PointerByReference();
		IntByReference xReturn = new IntByReference();
		IntByReference yReturn = new IntByReference();
		IntByReference widthReturn = new IntByReference();
		IntByReference heightReturn = new IntByReference();
		IntByReference borderWidthReturn = new IntByReference();
		IntByReference depthReturn = new IntByReference();

		int result = X11.INSTANCE.XGetGeometry(
			display, window,
			rootReturn, xReturn, yReturn,
			widthReturn, heightReturn,
			borderWidthReturn, depthReturn
		);

		if (result == 0) {
			return null;
		}

		// Get absolute position on screen
		Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
		IntByReference absX = new IntByReference();
		IntByReference absY = new IntByReference();
		PointerByReference childReturn = new PointerByReference();

		X11.INSTANCE.XTranslateCoordinates(
			display, window, root,
			0, 0,
			absX, absY,
			childReturn
		);

		return new Rectangle(
			absX.getValue(),
			absY.getValue(),
			widthReturn.getValue(),
			heightReturn.getValue()
		);
	}

	/**
	 * Check if a window is viewable
	 */
	public static boolean isWindowViewable(Pointer display, Pointer window) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return false;
		}

		X11.XWindowAttributes attrs = new X11.XWindowAttributes();
		int result = X11.INSTANCE.XGetWindowAttributes(display, window, attrs);

		return result != 0 && attrs.map_state == X11.IsViewable;
	}

	/**
	 * Get the active window using _NET_ACTIVE_WINDOW
	 */
	public static Pointer getActiveWindow(Pointer display) {
		Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
		Pointer activeWindowAtom = X11.INSTANCE.XInternAtom(display, "_NET_ACTIVE_WINDOW", false);

		PointerByReference actualTypeReturn = new PointerByReference();
		IntByReference actualFormatReturn = new IntByReference();
		IntByReference nItemsReturn = new IntByReference();
		IntByReference bytesAfterReturn = new IntByReference();
		PointerByReference propReturn = new PointerByReference();

		int result = X11.INSTANCE.XGetWindowProperty(
			display, root, activeWindowAtom,
			0, 1, false,
			new Pointer(X11.AnyPropertyType),
			actualTypeReturn, actualFormatReturn,
			nItemsReturn, bytesAfterReturn, propReturn
		);

		if (result == X11.Success && nItemsReturn.getValue() > 0) {
			Pointer prop = propReturn.getValue();
			if (prop != null) {
				long windowId = prop.getLong(0);
				X11.INSTANCE.XFree(prop);
				return new Pointer(windowId);
			}
		}

		return null;
	}

	/**
	 * Get client list (all managed windows)
	 */
	public static Pointer[] getClientList(Pointer display) {
		Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
		Pointer clientListAtom = X11.INSTANCE.XInternAtom(display, "_NET_CLIENT_LIST", false);

		PointerByReference actualTypeReturn = new PointerByReference();
		IntByReference actualFormatReturn = new IntByReference();
		IntByReference nItemsReturn = new IntByReference();
		IntByReference bytesAfterReturn = new IntByReference();
		PointerByReference propReturn = new PointerByReference();

		int result = X11.INSTANCE.XGetWindowProperty(
			display, root, clientListAtom,
			0, 1024, false,
			new Pointer(X11.AnyPropertyType),
			actualTypeReturn, actualFormatReturn,
			nItemsReturn, bytesAfterReturn, propReturn
		);

		if (result == X11.Success && nItemsReturn.getValue() > 0) {
			Pointer prop = propReturn.getValue();
			if (prop != null) {
				int count = nItemsReturn.getValue();
				Pointer[] windows = new Pointer[count];

				for (int i = 0; i < count; i++) {
					long windowId = prop.getLong(i * 8L);
					windows[i] = new Pointer(windowId);
				}

				X11.INSTANCE.XFree(prop);
				return windows;
			}
		}

		return new Pointer[0];
	}

	/**
	 * Check if window has override redirect (popup, menu, etc.)
	 */
	public static boolean hasOverrideRedirect(Pointer display, Pointer window) {
		X11.XWindowAttributes attrs = new X11.XWindowAttributes();
		int result = X11.INSTANCE.XGetWindowAttributes(display, window, attrs);
		return result != 0 && attrs.override_redirect;
	}

	/**
	 * Set {@code _NET_WM_BYPASS_COMPOSITOR = 2} on {@code window}: EWMH "the compositor should never
	 * unredirect this window". On X11 KDE, a fullscreen OpenGL game (e.g. a Proton/Wine title) otherwise
	 * makes KWin suspend compositing, which destroys every window's off-screen backing pixmap — so
	 * {@code captureViaComposite} reads black for the game <em>and</em> every other window. Marking the
	 * target with value 2 keeps KWin compositing it, so its pixmap stays readable. Best-effort: swallows
	 * errors (harmless when no compositor honors the hint). Idempotent — safe to call before every capture.
	 */
	public static void setKeepComposited(Pointer display, Pointer window) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return;
		}
		try {
			Pointer prop = X11.INSTANCE.XInternAtom(display, "_NET_WM_BYPASS_COMPOSITOR", false);
			if (prop == null || Pointer.nativeValue(prop) == 0) {
				return;
			}
			// format 32 => the data buffer is an array of C long (8 bytes each on 64-bit); one CARDINAL = 2.
			com.sun.jna.Memory data = new com.sun.jna.Memory(com.sun.jna.Native.LONG_SIZE);
			data.setNativeLong(0, new com.sun.jna.NativeLong(2));
			X11.INSTANCE.XChangeProperty(display, window, prop, new Pointer(X11.XA_CARDINAL), 32,
				X11.PropModeReplace, data, 1);
			X11.INSTANCE.XFlush(display);
		} catch (Throwable ignored) {
			// Best-effort hint; capture still proceeds (and falls back) without it.
		}
	}

	/**
	 * Force {@code window} to stack <em>above fullscreen</em> windows. A plain always-on-top window
	 * (EWMH {@code _NET_WM_STATE_ABOVE}) is still ranked <em>below</em> a window holding
	 * {@code _NET_WM_STATE_FULLSCREEN}, so a Studio overlay disappears behind a fullscreen game. Two
	 * best-effort nudges the WM stacks over fullscreen:
	 * <ul>
	 *   <li>set {@code _NET_WM_WINDOW_TYPE} = {@code _NET_WM_WINDOW_TYPE_NOTIFICATION} — notification
	 *       surfaces are drawn over fullscreen by mutter/KWin/most EWMH WMs. Crucially, most WMs read this
	 *       type <em>only at map time</em>, so when the overlay is already mapped we must
	 *       <em>unmap → set the property → remap</em> to force a re-read; a bare {@code XChangeProperty} on a
	 *       live window is silently ignored (the bug this fixes). We only remap when the type isn't already
	 *       notification, so the re-raise loop's repeat calls don't flicker;</li>
	 *   <li>send a {@code _NET_WM_STATE} <em>ADD {@code _NET_WM_STATE_ABOVE}</em> client message to the
	 *       root (same delivery path as {@code _NET_ACTIVE_WINDOW}), then {@code XRaiseWindow}.</li>
	 * </ul>
	 * Everything is swallowed on error (harmless on a WM that ignores the hints, or a true exclusive-fullscreen
	 * Wine/Proton game that bypasses the WM entirely). Idempotent.
	 */
	public static void promoteAboveFullscreen(Pointer display, Pointer window) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return;
		}
		try {
			// 1. Window type NOTIFICATION — stacked above fullscreen by most WMs. Remap so the WM re-reads it.
			Pointer typeProp = X11.INSTANCE.XInternAtom(display, "_NET_WM_WINDOW_TYPE", false);
			Pointer notif = X11.INSTANCE.XInternAtom(display, "_NET_WM_WINDOW_TYPE_NOTIFICATION", false);
			if (typeProp != null && Pointer.nativeValue(typeProp) != 0
				&& notif != null && Pointer.nativeValue(notif) != 0
				&& !isWindowType(display, window, typeProp, notif)) {
				boolean viewable = isWindowViewable(display, window);
				if (viewable) {
					X11.INSTANCE.XUnmapWindow(display, window);   // force the WM to re-read the type on remap
				}
				com.sun.jna.Memory data = new com.sun.jna.Memory(com.sun.jna.Native.LONG_SIZE);
				data.setNativeLong(0, new com.sun.jna.NativeLong(Pointer.nativeValue(notif)));
				X11.INSTANCE.XChangeProperty(display, window, typeProp, new Pointer(X11.XA_ATOM), 32,
					X11.PropModeReplace, data, 1);
				if (viewable) {
					X11.INSTANCE.XMapWindow(display, window);
				}
				X11.INSTANCE.XFlush(display);
			}

			// 2. _NET_WM_STATE: ADD _NET_WM_STATE_ABOVE via a root client message.
			Pointer stateAtom = X11.INSTANCE.XInternAtom(display, "_NET_WM_STATE", true);
			Pointer above = X11.INSTANCE.XInternAtom(display, "_NET_WM_STATE_ABOVE", true);
			if (stateAtom != null && Pointer.nativeValue(stateAtom) != 0
				&& above != null && Pointer.nativeValue(above) != 0) {
				Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
				X11.XClientMessageEvent ev = new X11.XClientMessageEvent();
				ev.type = X11.ClientMessage;
				ev.send_event = 1;
				ev.display = display;
				ev.window = new com.sun.jna.NativeLong(Pointer.nativeValue(window));
				ev.message_type = new com.sun.jna.NativeLong(Pointer.nativeValue(stateAtom));
				ev.format = 32;
				ev.data[0] = X11._NET_WM_STATE_ADD;
				ev.data[1] = Pointer.nativeValue(above);
				ev.data[2] = 0;                 // no second state
				ev.data[3] = 1;                 // source indication: application
				ev.data[4] = 0;
				long mask = X11.SubstructureRedirectMask | X11.SubstructureNotifyMask;
				X11.INSTANCE.XSendEvent(display, root, false, new com.sun.jna.NativeLong(mask), ev);
			}

			X11.INSTANCE.XRaiseWindow(display, window);
			X11.INSTANCE.XFlush(display);
		} catch (Throwable ignored) {
			// Best-effort; the overlay still shows (just possibly under a fullscreen window).
		}
	}

	/**
	 * True when {@code window}'s {@code typeProp} ({@code _NET_WM_WINDOW_TYPE}, an ATOM array) already
	 * contains {@code wanted}. Lets {@link #promoteAboveFullscreen} skip the unmap/remap on repeat calls.
	 */
	private static boolean isWindowType(Pointer display, Pointer window, Pointer typeProp, Pointer wanted) {
		PointerByReference actualType = new PointerByReference();
		IntByReference actualFormat = new IntByReference();
		IntByReference nItems = new IntByReference();
		IntByReference bytesAfter = new IntByReference();
		PointerByReference prop = new PointerByReference();
		int result = X11.INSTANCE.XGetWindowProperty(
			display, window, typeProp,
			0, 32, false,
			new Pointer(X11.XA_ATOM),
			actualType, actualFormat, nItems, bytesAfter, prop);
		if (result != X11.Success || nItems.getValue() <= 0) {
			return false;
		}
		Pointer p = prop.getValue();
		if (p == null) {
			return false;
		}
		try {
			long wantedId = Pointer.nativeValue(wanted);
			for (int i = 0; i < nItems.getValue(); i++) {
				if (p.getLong(i * 8L) == wantedId) {
					return true;
				}
			}
			return false;
		} finally {
			X11.INSTANCE.XFree(p);
		}
	}
}
