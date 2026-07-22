package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import com.botmaker.shared.capture.linux.input.LinuxInputBackend;
import com.botmaker.shared.capture.linux.input.UinputBackend;
import com.botmaker.shared.capture.linux.input.XSendEventBackend;
import com.botmaker.shared.capture.linux.input.XTestBackend;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux implementation of NativeController using X11
 * Uses XGetImage for window capture (portal/prompt-free on X11/XWayland) and XTest for mouse clicks
 *
 * Note: This class manages native X11 resources. While it implements AutoCloseable,
 * the display connection is typically kept open for the lifetime of the application.
 * Call close() explicitly if you need to release resources before application exit.
 *
 * <p>{@link #captureWindow} returns {@code null} when it cannot produce a usable frame (X11
 * unavailable, invalid geometry, or a capture error — e.g. native Wayland where Robot returns black).
 * Callers apply their own full-desktop fallback.
 *
 * <p><b>Input synthesis</b> is delegated to a pluggable {@link LinuxInputBackend} chosen by the
 * {@code botmaker.linux.input} system property (or {@code BOTMAKER_LINUX_INPUT} env var):
 * <ul>
 *   <li>{@code auto} (default) / {@code xsendevent} — {@link XSendEventBackend}: cursor-preserving,
 *       background clicks to X11/XWayland windows. The real pointer is never moved.</li>
 *   <li>{@code uinput} — {@link UinputBackend}: reliable everywhere (incl. native Wayland/games) but moves
 *       the shared cursor; falls back to xsendevent if {@code /dev/uinput} can't be opened.</li>
 *   <li>{@code xtest} — {@link XTestBackend}: the legacy warp-and-click (moves the cursor).</li>
 * </ul>
 * {@code auto} never selects a cursor-moving backend.
 */
public class LinuxController implements NativeController, AutoCloseable {

	private final Pointer display;
	private final boolean x11Available;
	private final LinuxInputBackend inputBackend;
	private volatile boolean closed = false;

	public LinuxController() {
		// Try to open X11 display
		Pointer disp = null;
		boolean available = false;

		try {
			disp = X11.INSTANCE.XOpenDisplay(null);
			available = (disp != null);

			if (!available) {
				System.err.println("[Linux] Warning: Could not open X11 display. Falling back to Robot for all operations.");
				System.err.println("[Linux] Make sure DISPLAY environment variable is set and X11 is running.");
			}
		} catch (UnsatisfiedLinkError e) {
			System.err.println("[Linux] Warning: X11 libraries not found. Install libx11-6 and libxtst-6.");
			System.err.println("[Linux] Falling back to Robot for all operations.");
		} catch (Exception e) {
			System.err.println("[Linux] Warning: Error initializing X11: " + e.getMessage());
		}

		this.display = disp;
		this.x11Available = available;
		this.inputBackend = available ? selectBackend(disp) : null;
	}

	/** Pick the input backend from {@code botmaker.linux.input} (default {@code auto} → cursor-safe xsendevent). */
	private static LinuxInputBackend selectBackend(Pointer display) {
		String env = System.getenv("BOTMAKER_LINUX_INPUT");
		String choice = System.getProperty("botmaker.linux.input", env != null ? env : "auto")
			.trim().toLowerCase();

		LinuxInputBackend backend;
		switch (choice) {
			case "xtest":
				backend = new XTestBackend(display);
				break;
			case "uinput": {
				int screen = X11.INSTANCE.XDefaultScreen(display);
				int w = X11.INSTANCE.XDisplayWidth(display, screen);
				int h = X11.INSTANCE.XDisplayHeight(display, screen);
				UinputBackend u = UinputBackend.tryCreate(w, h, display);
				if (u == null) {
					System.err.println("[Linux] uinput unavailable (can't open /dev/uinput); "
						+ "falling back to cursor-safe xsendevent.");
					backend = new XSendEventBackend(display);
				} else {
					backend = u;
				}
				break;
			}
			case "auto":
			case "xsendevent":
			default:
				backend = new XSendEventBackend(display);
				break;
		}
		System.out.println("[Linux] input backend = " + backend.name()
			+ " (preservesCursor=" + backend.preservesCursor() + ")");
		return backend;
	}

	@Override
	public GenericWindow getForegroundWindow() {
		checkNotClosed();

		if (!x11Available) {
			System.out.println("[Linux] X11 not available, returning mock window.");
			return new GenericWindow(-1, "Mock Linux Window", new Rectangle(0, 0, 800, 600));
		}

		try {
			// Try to get active window via _NET_ACTIVE_WINDOW (EWMH)
			Pointer activeWindow = X11Utils.getActiveWindow(display);

			if (activeWindow == null || Pointer.nativeValue(activeWindow) == 0) {
				// Fallback to XGetInputFocus
				PointerByReference focusReturn = new PointerByReference();
				IntByReference revertToReturn = new IntByReference();
				X11.INSTANCE.XGetInputFocus(display, focusReturn, revertToReturn);
				activeWindow = focusReturn.getValue();
			}

			if (activeWindow == null || Pointer.nativeValue(activeWindow) == 0 || Pointer.nativeValue(activeWindow) == 1) {
				System.out.println("[Linux] No active window found.");
				return null;
			}

			return toGenericWindow(activeWindow);
		} catch (Exception e) {
			System.err.println("[Linux] Error getting foreground window: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public List<GenericWindow> getChildWindows(GenericWindow parent) {
		checkNotClosed();

		List<GenericWindow> result = new ArrayList<>();

		if (!x11Available || parent == null) {
			return result;
		}

		try {
			Pointer parentWindow = (Pointer) parent.getNativeHandle();

			PointerByReference rootReturn = new PointerByReference();
			PointerByReference parentReturn = new PointerByReference();
			PointerByReference childrenReturn = new PointerByReference();
			IntByReference nChildrenReturn = new IntByReference();

			int status = X11.INSTANCE.XQueryTree(
				display, parentWindow,
				rootReturn, parentReturn,
				childrenReturn, nChildrenReturn
			);

			if (status == 0) {
				return result;
			}

			Pointer children = childrenReturn.getValue();
			int nChildren = nChildrenReturn.getValue();

			if (children != null && nChildren > 0) {
				long[] childIds = children.getLongArray(0, nChildren);

				for (long childId : childIds) {
					Pointer childWindow = new Pointer(childId);

					// Only include viewable windows with titles
					if (X11Utils.isWindowViewable(display, childWindow)) {
						String title = X11Utils.getWindowTitle(display, childWindow);
						if (title != null && !title.isEmpty()) {
							GenericWindow gw = toGenericWindow(childWindow);
							if (gw != null) {
								result.add(gw);
							}
						}
					}
				}

				X11.INSTANCE.XFree(children);
			}
		} catch (Exception e) {
			System.err.println("[Linux] Error getting child windows: " + e.getMessage());
			e.printStackTrace();
		}

		return result;
	}

	@Override
	public List<GenericWindow> getAllWindows() {
		return getAllWindows(false);
	}

	@Override
	public List<GenericWindow> getAllWindows(boolean includeMinimized) {
		checkNotClosed();

		List<GenericWindow> result = new ArrayList<>();

		if (!x11Available) {
			return result;
		}

		try {
			// Get all client windows from window manager
			Pointer[] windows = X11Utils.getClientList(display);

			if (windows.length == 0) {
				// Fallback: enumerate from root window
				Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
				windows = enumerateWindowsRecursive(root);
			}

			for (Pointer window : windows) {
				// Minimized windows are unmapped (not viewable) so their pixels can't be captured; include
				// them only when the caller intends to restore them first.
				if ((includeMinimized || X11Utils.isWindowViewable(display, window)) &&
					!X11Utils.hasOverrideRedirect(display, window)) {

					String title = X11Utils.getWindowTitle(display, window);
					if (title != null && !title.isEmpty()) {
						GenericWindow gw = toGenericWindow(window);
						if (gw != null) {
							result.add(gw);
						}
					}
				}
			}
		} catch (Exception e) {
			System.err.println("[Linux] Error getting all windows: " + e.getMessage());
			e.printStackTrace();
		}

		return result;
	}

	/**
	 * De-iconify a minimized window: {@code XMapWindow} restores it to Normal state (ICCCM 4.1.4), then we
	 * raise + focus it. After this the window is viewable and {@link #captureWindow} can read its pixels.
	 */
	@Override
	public void restoreWindow(GenericWindow window) {
		checkNotClosed();
		if (!x11Available || window == null) {
			return;
		}
		try {
			Pointer x11Window = (Pointer) window.getNativeHandle();
			X11.INSTANCE.XMapWindow(display, x11Window);
			X11.INSTANCE.XRaiseWindow(display, x11Window);
			X11.INSTANCE.XSetInputFocus(display, x11Window, X11.RevertToParent, 0);
			activateWindow(x11Window);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error restoring window: " + e.getMessage());
		}
	}

	/**
	 * Recursively enumerate windows (fallback method)
	 */
	private Pointer[] enumerateWindowsRecursive(Pointer window) {
		List<Pointer> allWindows = new ArrayList<>();

		try {
			PointerByReference rootReturn = new PointerByReference();
			PointerByReference parentReturn = new PointerByReference();
			PointerByReference childrenReturn = new PointerByReference();
			IntByReference nChildrenReturn = new IntByReference();

			int status = X11.INSTANCE.XQueryTree(
				display, window,
				rootReturn, parentReturn,
				childrenReturn, nChildrenReturn
			);

			if (status != 0) {
				Pointer children = childrenReturn.getValue();
				int nChildren = nChildrenReturn.getValue();

				if (children != null && nChildren > 0) {
					long[] childIds = children.getLongArray(0, nChildren);

					for (long childId : childIds) {
						Pointer childWindow = new Pointer(childId);
						allWindows.add(childWindow);

						// Recurse into children
						Pointer[] subWindows = enumerateWindowsRecursive(childWindow);
						for (Pointer sw : subWindows) {
							allWindows.add(sw);
						}
					}

					X11.INSTANCE.XFree(children);
				}
			}
		} catch (Exception e) {
			// Ignore errors during recursive enumeration
		}

		return allWindows.toArray(new Pointer[0]);
	}

	/**
	 * Capture a window by reading its X pixmap directly with {@link X11#XGetImage} against the window drawable.
	 * This is deliberately <b>not</b> AWT {@code Robot}: on Wayland every {@code Robot} grab tunnels through
	 * xdg-desktop-portal and pops a screen-share prompt (and then fails with a {@code SecurityException}),
	 * whereas {@code XGetImage} on an X11/XWayland window reads its pixels with no portal and no prompt.
	 * Returns {@code null} on failure so the caller can apply its own full-desktop fallback.
	 */
	@Override
	public BufferedImage captureWindow(GenericWindow window) {
		checkNotClosed();

		if (!x11Available || window == null) {
			return null;
		}

		X11.XImage image = null;
		try {
			Pointer x11Window = (Pointer) window.getNativeHandle();

			// Bail if the window isn't viewable (e.g. it was minimized/unmapped since it was enumerated):
			// XGetImage on an unmapped drawable raises a BadMatch that Xlib's default handler prints to
			// stderr. This cheap re-check avoids generating that error at the source. Callers that want a
			// minimized window shown must restoreWindow(...) first.
			if (!X11Utils.isWindowViewable(display, x11Window)) {
				return null;
			}

			// Geometry gives us the window's size and absolute screen position (via XTranslateCoordinates).
			Rectangle rect = X11Utils.getWindowGeometry(display, x11Window);
			if (rect == null || rect.width <= 0 || rect.height <= 0) {
				System.err.println("[Linux] Invalid window geometry, cannot capture window.");
				return null;
			}

			// Ask KWin to keep this window composited (EWMH _NET_WM_BYPASS_COMPOSITOR=2). A fullscreen
			// Proton/OpenGL game otherwise triggers unredirect, blacking out every window's backing pixmap.
			X11Utils.setKeepComposited(display, x11Window);

			// Prefer the window's off-screen pixmap (via XComposite) so regions occluded by windows in
			// front are captured too.
			image = captureViaComposite(x11Window, rect);
			BufferedImage result = decode(image);
			if (image != null) { image.destroyImage(); image = null; }

			if (result != null && !isAllBlack(result)) {
				return result;
			}

			// Composite read unavailable or all-black (compositor unredirected / mid-transition). The
			// root-window crop reads whatever is *visually* at the window's rect — which is correct only
			// for the foreground window. For a background/occluded window it returns the window sitting in
			// front, which is exactly what made every window look identical. So gate root-crop on foreground.
			if (isForeground(x11Window)) {
				image = X11.INSTANCE.XGetImage(display, X11.INSTANCE.XDefaultRootWindow(display),
						rect.x, rect.y, rect.width, rect.height,
						new com.sun.jna.NativeLong(X11.AllPlanes), X11.ZPixmap);
				BufferedImage rootCrop = decode(image);
				if (image != null) { image.destroyImage(); image = null; }
				if (rootCrop != null && !isAllBlack(rootCrop)) {
					return rootCrop;
				}
			}

			// On-window drawable: this window's own un-occluded pixels. Occluded regions read black, but it
			// is never *another* window's content — so background windows keep their own (partial) frame.
			image = X11.INSTANCE.XGetImage(display, x11Window, 0, 0, rect.width, rect.height,
					new com.sun.jna.NativeLong(X11.AllPlanes), X11.ZPixmap);
			BufferedImage onWindow = decode(image);
			if (image != null) { image.destroyImage(); image = null; }
			if (onWindow != null && !isAllBlack(onWindow)) {
				return onWindow;
			}

			// Nothing usable — return the composite frame (even if black) over null so the caller still
			// gets correct geometry rather than falling all the way back to a full-desktop capture.
			return result != null ? result : onWindow;

		} catch (Throwable e) {
			System.err.println("[Linux] Error capturing window: " + e.getMessage());
			return null;
		} finally {
			if (image != null) image.destroyImage();
		}
	}

	/** Whether {@code x11Window} is the EWMH active (foreground) window — the only one root-crop is valid for. */
	private boolean isForeground(Pointer x11Window) {
		try {
			Pointer active = X11Utils.getActiveWindow(display);
			return active != null && Pointer.nativeValue(active) == Pointer.nativeValue(x11Window);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Captures {@code x11Window} from its off-screen backing pixmap via XComposite, so pixels covered by
	 * windows in front are still read (unlike an on-window {@code XGetImage}, which returns black there).
	 * Returns {@code null} — so the caller falls back to the on-window path — when libXcomposite is missing,
	 * no compositor is running, or the pixmap can't be named/read.
	 */
	private X11.XImage captureViaComposite(Pointer x11Window, Rectangle rect) {
		XComposite xc = XComposite.instance();
		if (xc == null || !compositorActive()) {
			return null;
		}
		try {
			if (!xc.XCompositeQueryExtension(display, new IntByReference(), new IntByReference())) {
				return null;
			}
			Pointer pixmap = xc.XCompositeNameWindowPixmap(display, x11Window);
			if (pixmap == null || Pointer.nativeValue(pixmap) == 0) {
				return null;
			}
			try {
				return X11.INSTANCE.XGetImage(display, pixmap, 0, 0, rect.width, rect.height,
						new com.sun.jna.NativeLong(X11.AllPlanes), X11.ZPixmap);
			} finally {
				X11.INSTANCE.XFreePixmap(display, pixmap);
			}
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Whether a compositing manager owns the {@code _NET_WM_CM_S<screen>} selection. Only when one is running
	 * does a top-level window have a redirected off-screen pixmap for {@link #captureViaComposite} to read.
	 */
	private boolean compositorActive() {
		try {
			int screen = X11.INSTANCE.XDefaultScreen(display);
			Pointer atom = X11.INSTANCE.XInternAtom(display, "_NET_WM_CM_S" + screen, false);
			if (atom == null) {
				return false;
			}
			Pointer owner = X11.INSTANCE.XGetSelectionOwner(display, atom);
			return owner != null && Pointer.nativeValue(owner) != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	/** Null/validity-guards an {@link X11.XImage} then decodes it; returns {@code null} for an unusable frame. */
	private static BufferedImage decode(X11.XImage image) {
		if (image == null || image.data == null || image.bits_per_pixel < 24) {
			return null;
		}
		return toBufferedImage(image);
	}

	/**
	 * True if every sampled pixel is pure black — the signature of a capture read while the compositor had
	 * the window unredirected. Samples a sparse grid (≈every 17th pixel per axis) so it's cheap on large
	 * frames; a single non-black sample short-circuits to false.
	 */
	static boolean isAllBlack(BufferedImage img) {
		if (img == null) {
			return true;
		}
		int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 17);
		for (int y = 0; y < img.getHeight(); y += step) {
			for (int x = 0; x < img.getWidth(); x += step) {
				if ((img.getRGB(x, y) & 0x00FFFFFF) != 0) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Decodes a ZPixmap {@link X11.XImage} into a {@code TYPE_INT_ARGB} image, extracting channels via the
	 * image's {@code red/green/blue} masks (handles both 32- and 24-bit-per-pixel packings). Assumes the
	 * server's byte order matches the JVM (true for a local X/XWayland connection).
	 */
	private static BufferedImage toBufferedImage(X11.XImage image) {
		int w = image.width, h = image.height;
		if (w <= 0 || h <= 0) return null;

		int bpp = image.bits_per_pixel;
		int stride = image.bytes_per_line;
		int bytesPerPixel = bpp / 8;
		byte[] raw = image.data.getByteArray(0, stride * h);

		int redMask = (int) image.red_mask.longValue();
		int greenMask = (int) image.green_mask.longValue();
		int blueMask = (int) image.blue_mask.longValue();
		int redShift = Integer.numberOfTrailingZeros(redMask == 0 ? 0xFF0000 : redMask);
		int greenShift = Integer.numberOfTrailingZeros(greenMask == 0 ? 0x00FF00 : greenMask);
		int blueShift = Integer.numberOfTrailingZeros(blueMask == 0 ? 0x0000FF : blueMask);

		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		int[] px = ((java.awt.image.DataBufferInt) out.getRaster().getDataBuffer()).getData();
		for (int y = 0; y < h; y++) {
			int rowStart = y * stride;
			int outRow = y * w;
			for (int x = 0; x < w; x++) {
				int p = rowStart + x * bytesPerPixel;
				// Little-endian assembly of the pixel value from its bytes.
				int pixel = (raw[p] & 0xFF) | ((raw[p + 1] & 0xFF) << 8) | ((raw[p + 2] & 0xFF) << 16);
				if (bytesPerPixel >= 4) pixel |= (raw[p + 3] & 0xFF) << 24;
				int r = (pixel & redMask) >>> redShift;
				int g = (pixel & greenMask) >>> greenShift;
				int b = (pixel & blueMask) >>> blueShift;
				px[outRow + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		return out;
	}

	/**
	 * Post a left click at window-relative coordinates via the selected input backend. With the default
	 * {@code xsendevent} backend this delivers the click straight to the window without moving the real
	 * cursor, so it works in the background.
	 */
	@Override
	public void postLeftClick(GenericWindow window, int relativeX, int relativeY) {
		checkNotClosed();
		if (inputBackend == null || window == null) {
			return;
		}
		inputBackend.clickWindow((Pointer) window.getNativeHandle(), relativeX, relativeY, 1);
	}

	/**
	 * Post a left click at absolute screen coordinates via the selected input backend. The default
	 * {@code xsendevent} backend resolves the window under the point and clicks it without touching the
	 * real cursor; {@code uinput}/{@code xtest} move the shared cursor.
	 */
	@Override
	public void postLeftClickScreen(int xAbs, int yAbs) {
		checkNotClosed();
		if (inputBackend == null) {
			System.out.println("[Linux] X11 not available, cannot post click.");
			return;
		}
		inputBackend.clickScreen(xAbs, yAbs, 1);
	}

	@Override
	public void focusWindow(GenericWindow window) {
		checkNotClosed();
		if (!x11Available || window == null) {
			return;
		}
		try {
			Pointer x11Window = (Pointer) window.getNativeHandle();
			X11.INSTANCE.XRaiseWindow(display, x11Window);
			X11.INSTANCE.XSetInputFocus(display, x11Window, X11.RevertToParent, 0);
			activateWindow(x11Window);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error focusing window: " + e.getMessage());
		}
	}

	/**
	 * Best-effort EWMH activation: send a {@code _NET_ACTIVE_WINDOW} client message to the root window so the
	 * window manager brings {@code x11Window} to the foreground. Many reparenting/EWMH WMs ignore a bare
	 * {@code XRaiseWindow}/{@code XSetInputFocus} on a background window and only honour this request. No-op
	 * (silently) when the WM doesn't advertise the atom.
	 */
	private void activateWindow(Pointer x11Window) {
		try {
			Pointer atom = X11.INSTANCE.XInternAtom(display, "_NET_ACTIVE_WINDOW", true);
			if (atom == null || Pointer.nativeValue(atom) == 0) {
				return; // non-EWMH WM
			}
			Pointer root = X11.INSTANCE.XDefaultRootWindow(display);
			X11.XClientMessageEvent ev = new X11.XClientMessageEvent();
			ev.type = X11.ClientMessage;
			ev.send_event = 1;
			ev.display = display;
			ev.window = new com.sun.jna.NativeLong(Pointer.nativeValue(x11Window));
			ev.message_type = new com.sun.jna.NativeLong(Pointer.nativeValue(atom));
			ev.format = 32;
			ev.data[0] = 2;                 // source indication: pager (honoured past focus-stealing prevention)
			ev.data[1] = X11.CurrentTime;   // timestamp
			ev.data[2] = 0;                 // requestor's currently-active window (none)
			ev.data[3] = 0;
			ev.data[4] = 0;
			long mask = X11.SubstructureRedirectMask | X11.SubstructureNotifyMask;
			X11.INSTANCE.XSendEvent(display, root, false, new com.sun.jna.NativeLong(mask), ev);
		} catch (Exception e) {
			// best-effort — leave the plain XRaiseWindow/XSetInputFocus result in place
		}
	}

	@Override
	public void moveWindow(GenericWindow window, int x, int y) {
		checkNotClosed();
		if (!x11Available || window == null) {
			return;
		}
		try {
			X11.INSTANCE.XMoveWindow(display, (Pointer) window.getNativeHandle(), x, y);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error moving window: " + e.getMessage());
		}
	}

	@Override
	public void resizeWindow(GenericWindow window, int width, int height) {
		checkNotClosed();
		if (!x11Available || window == null) {
			return;
		}
		try {
			X11.INSTANCE.XResizeWindow(display, (Pointer) window.getNativeHandle(), width, height);
			X11.INSTANCE.XFlush(display);
		} catch (Exception e) {
			System.err.println("[Linux] Error resizing window: " + e.getMessage());
		}
	}

	@Override
	public void promoteOverlayAboveFullscreen(String windowTitle) {
		checkNotClosed();
		if (!x11Available || windowTitle == null || windowTitle.isEmpty()) {
			return;
		}
		try {
			Pointer[] windows = X11Utils.getClientList(display);
			if (windows == null) {
				return;
			}
			for (Pointer w : windows) {
				if (windowTitle.equals(X11Utils.getWindowTitle(display, w))) {
					X11Utils.promoteAboveFullscreen(display, w);
				}
			}
		} catch (Exception e) {
			System.err.println("[Linux] promoteOverlayAboveFullscreen failed: " + e.getMessage());
		}
	}

	// --- Input synthesis (delegated to the selected LinuxInputBackend) ---

	private static final int KEYSYM_SHIFT_L = 0xFFE1;

	@Override
	public void keyDown(int nativeKeyCode) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.key(nativeKeyCode, true);
		}
	}

	@Override
	public void keyUp(int nativeKeyCode) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.key(nativeKeyCode, false);
		}
	}

	@Override
	public void typeText(String text) {
		checkNotClosed();
		if (inputBackend == null || text == null) {
			return;
		}
		typeVia(null, text);
	}

	// --- Targeted key synthesis (route to a specific window's client via the backend) ---

	@Override
	public void keyDown(GenericWindow window, int nativeKeyCode) {
		checkNotClosed();
		if (inputBackend != null) {
			keyVia(handleOf(window), nativeKeyCode, true);
		}
	}

	@Override
	public void keyUp(GenericWindow window, int nativeKeyCode) {
		checkNotClosed();
		if (inputBackend != null) {
			keyVia(handleOf(window), nativeKeyCode, false);
		}
	}

	@Override
	public void typeText(GenericWindow window, String text) {
		checkNotClosed();
		if (inputBackend == null || text == null) {
			return;
		}
		typeVia(handleOf(window), text);
	}

	/** Type {@code text} into {@code window} (or the focused window when {@code null}), shifting uppercase. */
	private void typeVia(Pointer window, String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			// For Latin-1 the X keysym equals the code point; uppercase letters need Shift held.
			boolean needShift = Character.isUpperCase(c);
			if (needShift) {
				keyVia(window, KEYSYM_SHIFT_L, true);
			}
			keyVia(window, c, true);
			keyVia(window, c, false);
			if (needShift) {
				keyVia(window, KEYSYM_SHIFT_L, false);
			}
		}
	}

	/** Deliver one key event to {@code window}, or the focused window when {@code null}. */
	private void keyVia(Pointer window, int keysym, boolean press) {
		if (window == null) {
			inputBackend.key(keysym, press);
		} else {
			inputBackend.key(window, keysym, press);
		}
	}

	private static Pointer handleOf(GenericWindow window) {
		return window == null ? null : (Pointer) window.getNativeHandle();
	}

	@Override
	public void mouseMove(int xAbs, int yAbs) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.move(xAbs, yAbs);
		}
	}

	@Override
	public void mouseButton(int button, boolean press) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.button(button, press);
		}
	}

	@Override
	public void scroll(int amount) {
		checkNotClosed();
		if (inputBackend != null) {
			inputBackend.scroll(amount);
		}
	}

	/**
	 * Convert X11 window to GenericWindow
	 */
	private GenericWindow toGenericWindow(Pointer window) {
		if (window == null || Pointer.nativeValue(window) == 0) {
			return null;
		}

		try {
			String title = X11Utils.getWindowTitle(display, window);
			Rectangle rect = X11Utils.getWindowGeometry(display, window);

			if (rect == null) {
				rect = new Rectangle(0, 0, 0, 0);
			}

			return new GenericWindow(window, title != null ? title : "", rect);
		} catch (Exception e) {
			System.err.println("[Linux] Error converting to GenericWindow: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Cleanup X11 resources
	 * This method is safe to call multiple times.
	 */
	@Override
	public void close() {
		if (!closed && x11Available && display != null) {
			synchronized (this) {
				if (!closed) {
					try {
						if (inputBackend != null) {
							inputBackend.close(); // destroy the uinput device, if any
						}
					} catch (Exception e) {
						System.err.println("[Linux] Error closing input backend: " + e.getMessage());
					}
					try {
						X11.INSTANCE.XCloseDisplay(display);
					} catch (Exception e) {
						System.err.println("[Linux] Error closing X11 display: " + e.getMessage());
					} finally {
						closed = true;
					}
				}
			}
		}
	}

	/** True if the active input backend leaves the user's real cursor untouched (background-capable). */
	@Override
	public boolean supportsBackgroundInput() {
		return inputBackend != null && inputBackend.preservesCursor();
	}

	/**
	 * Check if resources have been closed
	 */
	private void checkNotClosed() {
		if (closed) {
			throw new IllegalStateException("LinuxController has been closed and cannot be used");
		}
	}
}
