package com.botmaker.shared.capture.linux;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.List;

/**
 * JNA bindings for X11 library (libX11)
 * Used for window management and querying on Linux
 *
 * <p>Includes {@link #XGetImage} (+ the {@link XImage} struct) so window capture can read a window's pixmap
 * directly. Unlike AWT {@code Robot} — which on Wayland tunnels through xdg-desktop-portal and triggers a
 * screen-share prompt per grab — {@code XGetImage} against an X11/XWayland window drawable reads its pixels
 * with no portal and no prompt.
 */
public interface X11 extends Library {
	X11 INSTANCE = Native.load("X11", X11.class);

	// Constants
	int None = 0;
	int AnyPropertyType = 0;
	int Success = 0;
	int InputFocus = 1;
	int RevertToParent = 2;

	// XChangeProperty mode + the predefined CARDINAL atom (X.h / Xatom.h). Predefined atoms are small fixed
	// XIDs, so XA_CARDINAL is passed as {@code new Pointer(6)} rather than being interned.
	int PropModeReplace = 0;
	int XA_CARDINAL = 6;

	// XGetImage: image formats (Xlib.h) and the "all planes" mask.
	int XYBitmap = 0;
	int XYPixmap = 1;
	int ZPixmap = 2;
	long AllPlanes = 0xFFFFFFFFFFFFFFFFL;

	int IsUnmapped = 0;
	int IsUnviewable = 1;
	int IsViewable = 2;

	// Event types (X.h)
	int KeyPress = 2;
	int KeyRelease = 3;
	int ButtonPress = 4;
	int ButtonRelease = 5;
	int MotionNotify = 6;
	int ClientMessage = 33;

	// Event mask bits (X.h) — used as the XSendEvent event_mask so the event is delivered
	// to the window's owning client even when we target a specific window.
	long KeyPressMask = 1L << 0;
	long KeyReleaseMask = 1L << 1;
	long ButtonPressMask = 1L << 2;
	long ButtonReleaseMask = 1L << 3;
	long PointerMotionMask = 1L << 6;
	// Root-window event masks for EWMH client messages (X.h) — a _NET_ACTIVE_WINDOW request is sent to the
	// root with SubstructureRedirect|SubstructureNotify so the window manager receives and honours it.
	long SubstructureNotifyMask = 1L << 19;
	long SubstructureRedirectMask = 1L << 20;

	long CurrentTime = 0L;

	// Display management
	Pointer XOpenDisplay(String displayName);
	int XCloseDisplay(Pointer display);
	Pointer XDefaultRootWindow(Pointer display);
	int XDefaultScreen(Pointer display);
	Pointer XRootWindow(Pointer display, int screenNumber);
	int XFlush(Pointer display);
	int XSync(Pointer display, boolean discard);

	// Window queries
	int XQueryTree(Pointer display, Pointer window,
				   PointerByReference rootReturn,
				   PointerByReference parentReturn,
				   PointerByReference childrenReturn,
				   IntByReference nChildrenReturn);

	int XGetWindowProperty(Pointer display, Pointer window,
						   Pointer property, long longOffset, long longLength,
						   boolean delete, Pointer reqType,
						   PointerByReference actualTypeReturn,
						   IntByReference actualFormatReturn,
						   IntByReference nItemsReturn,
						   IntByReference bytesAfterReturn,
						   PointerByReference propReturn);

	// Window attributes
	int XGetWindowAttributes(Pointer display, Pointer window, XWindowAttributes attributes);

	int XGetGeometry(Pointer display, Pointer drawable,
					 PointerByReference rootReturn,
					 IntByReference xReturn, IntByReference yReturn,
					 IntByReference widthReturn, IntByReference heightReturn,
					 IntByReference borderWidthReturn,
					 IntByReference depthReturn);

	int XTranslateCoordinates(Pointer display, Pointer srcWindow, Pointer destWindow,
							  int srcX, int srcY,
							  IntByReference destXReturn, IntByReference destYReturn,
							  PointerByReference childReturn);

	// Pointer query (root-window coordinates, matching XTest's coordinate space)
	boolean XQueryPointer(Pointer display, Pointer window,
						  PointerByReference rootReturn, PointerByReference childReturn,
						  IntByReference rootXReturn, IntByReference rootYReturn,
						  IntByReference winXReturn, IntByReference winYReturn,
						  IntByReference maskReturn);

	// Window focus
	int XGetInputFocus(Pointer display, PointerByReference focusReturn, IntByReference revertToReturn);
	int XSetInputFocus(Pointer display, Pointer focus, int revertTo, long time);

	// Synthetic event delivery. Sends {@code event} to {@code window}'s owning client without moving the
	// real pointer (unlike XTest). {@code propagate}=false + a matching {@code eventMask} delivers the
	// event to the client even if it hasn't selected for it. Returns 0 on failure (BadWindow/BadValue).
	int XSendEvent(Pointer display, Pointer window, boolean propagate, com.sun.jna.NativeLong eventMask,
				   XButtonEvent event);

	// Same C function, bound again with the ClientMessage layout — used to send an EWMH _NET_ACTIVE_WINDOW
	// request to the root window (activate/raise a window on WMs that ignore a bare XRaiseWindow).
	int XSendEvent(Pointer display, Pointer window, boolean propagate, com.sun.jna.NativeLong eventMask,
				   XClientMessageEvent event);

	// Window geometry mutation (move / resize)
	int XMoveWindow(Pointer display, Pointer window, int x, int y);
	int XResizeWindow(Pointer display, Pointer window, int width, int height);
	int XMoveResizeWindow(Pointer display, Pointer window, int x, int y, int width, int height);
	int XRaiseWindow(Pointer display, Pointer window);
	// Map (show) a window. On a top-level window that is iconified/minimized this de-iconifies it per
	// ICCCM 4.1.4 — the WM restores it to Normal state — so its pixels become readable by XGetImage again.
	int XMapWindow(Pointer display, Pointer window);

	// Install a process-wide Xlib error handler; returns the previous one. The default Xlib handler prints
	// non-fatal protocol errors (BadMatch/BadWindow/BadDrawable — e.g. XGetImage racing a window unmap) to
	// stderr; a no-op handler swallows that benign noise. See X11ErrorSilencer.
	Pointer XSetErrorHandler(XErrorHandler handler);

	/** Callback matching Xlib's {@code int (*)(Display*, XErrorEvent*)}. Args ignored when silencing. */
	interface XErrorHandler extends Callback {
		int invoke(Pointer display, Pointer errorEvent);
	}

	// Keyboard: map an X keysym to a physical keycode for XTest injection
	byte XKeysymToKeycode(Pointer display, long keysym);

	// Keyboard: the inverse — map a physical keycode back to a keysym for a given shift level
	// ({@code index} 0 = unshifted, 1 = shifted). Deprecated in Xlib but still exported by libX11 and the
	// simplest way to decode a recorded key event; used by the X11 input listener. Returns 0 (NoSymbol) when
	// the keycode has no symbol at that level.
	com.sun.jna.NativeLong XKeycodeToKeysym(Pointer display, byte keycode, int index);

	// Atoms
	Pointer XInternAtom(Pointer display, String atomName, boolean onlyIfExists);
	int XGetAtomName(Pointer display, Pointer atom, PointerByReference nameReturn);

	// Write a window property. For format 32 the data buffer is an array of C {@code long} (8 bytes each on
	// 64-bit), not int32. Used to set _NET_WM_BYPASS_COMPOSITOR=2 so KWin never unredirects the target
	// (keeps it composited, so its off-screen pixmap stays readable — see X11Utils.setKeepComposited).
	int XChangeProperty(Pointer display, Pointer window, Pointer property, Pointer type, int format,
						int mode, Pointer data, int nElements);

	// Selections — used to detect a running compositor via the _NET_WM_CM_S<screen> selection owner.
	// Returns the owning window (XID as Pointer), or null (None) when the selection is unowned.
	Pointer XGetSelectionOwner(Pointer display, Pointer selectionAtom);

	// Frees a Pixmap XID (e.g. the one named by XCompositeNameWindowPixmap for occlusion-safe capture).
	int XFreePixmap(Pointer display, Pointer pixmap);

	// Screen info
	int XScreenCount(Pointer display);
	int XDisplayWidth(Pointer display, int screenNumber);
	int XDisplayHeight(Pointer display, int screenNumber);

	// Image capture — reads a drawable's pixels directly (no portal/prompt on X11/XWayland). Returns a
	// pointer to a heap XImage; free it via the struct's f.destroy_image function pointer (see LinuxController).
	XImage XGetImage(Pointer display, Pointer drawable, int x, int y, int width, int height,
					 com.sun.jna.NativeLong planeMask, int format);

	// Memory management
	int XFree(Pointer data);

	// Utility
	int XFetchName(Pointer display, Pointer window, PointerByReference nameReturn);

	/**
	 * XButtonEvent / XKeyEvent — same native layout in Xlib.h (the trailing {@code button} field doubles as
	 * {@code keycode} for KeyPress/KeyRelease). Passed to {@link #XSendEvent} to deliver synthetic pointer or
	 * key input to a specific window without touching the real cursor.
	 *
	 * <p>The native {@code XEvent} is a union sized to its largest member (192 bytes on 64-bit). {@code
	 * XSendEvent} reads {@code sizeof(XEvent)} bytes from the pointer, so this struct is padded past that size
	 * with {@link #pad} to guarantee the read stays inside our buffer. Fields follow the Xlib.h order; JNA
	 * applies the natural C alignment (e.g. {@code serial}/{@code display} align to 8 on 64-bit).
	 */
	class XButtonEvent extends Structure {
		public int type;                       // ButtonPress/ButtonRelease/KeyPress/KeyRelease/MotionNotify
		public com.sun.jna.NativeLong serial;  // unsigned long — # of last request processed by server
		public int send_event;                 // Bool — set true by the server for XSendEvent'd events
		public Pointer display;                // Display* the event was read from
		public com.sun.jna.NativeLong window;  // Window the event is reported relative to (XID)
		public com.sun.jna.NativeLong root;    // root window that the event occurred on
		public com.sun.jna.NativeLong subwindow;
		public com.sun.jna.NativeLong time;    // Time — milliseconds
		public int x, y;                       // pointer position, window-relative
		public int x_root, y_root;             // pointer position, root-relative (screen)
		public int state;                      // key/button mask
		public int button;                     // button (1..5) — or keycode for KeyPress/KeyRelease
		public int same_screen;                // Bool
		// Pad past sizeof(XEvent) (192B on 64-bit) so XSendEvent never reads past our buffer.
		public byte[] pad = new byte[128];

		@Override
		protected List<String> getFieldOrder() {
			return Arrays.asList("type", "serial", "send_event", "display", "window", "root", "subwindow",
				"time", "x", "y", "x_root", "y_root", "state", "button", "same_screen", "pad");
		}
	}

	/**
	 * XClientMessageEvent (Xlib.h) — a client message delivered via {@link #XSendEvent}. Used to send an EWMH
	 * {@code _NET_ACTIVE_WINDOW} request to the root window so the window manager activates/raises a target
	 * (bare {@code XRaiseWindow} is ignored by many reparenting/EWMH WMs). For format 32 the {@code data} union
	 * is five C {@code long}s (8 bytes each on 64-bit); {@code l[0]} is the source indication (2 = pager, which
	 * WMs honour past focus-stealing prevention). Padded past {@code sizeof(XEvent)} (192B) like {@link XButtonEvent}.
	 */
	class XClientMessageEvent extends Structure {
		public int type;                       // ClientMessage
		public com.sun.jna.NativeLong serial;
		public int send_event;                 // Bool
		public Pointer display;
		public com.sun.jna.NativeLong window;  // target window (XID)
		public com.sun.jna.NativeLong message_type; // the message atom (e.g. _NET_ACTIVE_WINDOW)
		public int format;                     // 8/16/32 — 32 for _NET_ACTIVE_WINDOW
		public long[] data = new long[5];      // data.l[0..4] (64-bit long matches C long on our targets)
		// Pad past sizeof(XEvent) (192B on 64-bit) so XSendEvent never reads past our buffer.
		public byte[] pad = new byte[128];

		@Override
		protected List<String> getFieldOrder() {
			return Arrays.asList("type", "serial", "send_event", "display", "window", "message_type",
				"format", "data", "pad");
		}
	}

	/**
	 * XImage — the pixel buffer returned by {@link #XGetImage} (Xlib.h layout). Fields run through the RGB
	 * channel masks (enough to decode a ZPixmap), then {@code obdata} and the six image-manipulation function
	 * pointers; the function table is flattened here so (a) the struct is sized correctly for the buffer the
	 * server allocated and (b) {@link #destroyImage} can be called to free it (that is what the Xlib
	 * {@code XDestroyImage} macro expands to — there is no plain library symbol).
	 *
	 * <p>{@code data} points at {@code bytes_per_line * height} bytes; a ZPixmap pixel is {@code bits_per_pixel}
	 * bits and its channels are extracted with {@code red_mask}/{@code green_mask}/{@code blue_mask}.
	 */
	class XImage extends Structure {
		public int width, height;
		public int xoffset;
		public int format;
		public Pointer data;
		public int byte_order;
		public int bitmap_unit;
		public int bitmap_bit_order;
		public int bitmap_pad;
		public int depth;
		public int bytes_per_line;
		public int bits_per_pixel;
		public com.sun.jna.NativeLong red_mask;
		public com.sun.jna.NativeLong green_mask;
		public com.sun.jna.NativeLong blue_mask;
		public Pointer obdata;
		// struct funcs { create_image, destroy_image, get_pixel, put_pixel, sub_image, add_pixel } — flattened.
		public Pointer f_create_image;
		public Pointer f_destroy_image;
		public Pointer f_get_pixel;
		public Pointer f_put_pixel;
		public Pointer f_sub_image;
		public Pointer f_add_pixel;

		@Override
		protected List<String> getFieldOrder() {
			return Arrays.asList("width", "height", "xoffset", "format", "data", "byte_order", "bitmap_unit",
				"bitmap_bit_order", "bitmap_pad", "depth", "bytes_per_line", "bits_per_pixel",
				"red_mask", "green_mask", "blue_mask", "obdata",
				"f_create_image", "f_destroy_image", "f_get_pixel", "f_put_pixel", "f_sub_image", "f_add_pixel");
		}

		/** Frees this image (data + struct) via its own {@code destroy_image} routine; best-effort. */
		public void destroyImage() {
			try {
				if (f_destroy_image != null) {
					com.sun.jna.Function.getFunction(f_destroy_image).invokeInt(new Object[]{getPointer()});
				}
			} catch (Throwable ignored) {
				// Fall back to freeing the data buffer at least; leaking the small struct is preferable to a crash.
				try { if (data != null) INSTANCE.XFree(data); } catch (Throwable ignored2) {}
			}
		}
	}

	/**
	 * XWindowAttributes structure
	 */
	class XWindowAttributes extends Structure {
		public int x, y;
		public int width, height;
		public int border_width;
		public int depth;
		public Pointer visual;
		public Pointer root;
		public int c_class;
		public int bit_gravity;
		public int win_gravity;
		public int backing_store;
		public long backing_planes;
		public long backing_pixel;
		public boolean save_under;
		public Pointer colormap;
		public boolean map_installed;
		public int map_state;
		public long all_event_masks;
		public long your_event_mask;
		public long do_not_propagate_mask;
		public boolean override_redirect;
		public Pointer screen;

		@Override
		protected List<String> getFieldOrder() {
			return Arrays.asList("x", "y", "width", "height", "border_width", "depth",
				"visual", "root", "c_class", "bit_gravity", "win_gravity",
				"backing_store", "backing_planes", "backing_pixel",
				"save_under", "colormap", "map_installed", "map_state",
				"all_event_masks", "your_event_mask", "do_not_propagate_mask",
				"override_redirect", "screen");
		}
	}
}
