package com.botmaker.shared.capture.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * JNA bindings for libXcomposite, used to capture a window's <em>off-screen</em> pixmap.
 *
 * <p>When a compositing manager is running, each top-level window's full contents are kept in an
 * off-screen backing pixmap. Reading pixels straight off the on-screen window drawable (plain
 * {@code XGetImage} on the window) returns <em>black</em> for any region occluded by a window in
 * front, because there is no on-screen backing store for the covered pixels. Naming and reading the
 * off-screen pixmap instead yields the whole window, occluded or not.
 *
 * <p>The library is loaded lazily and defensively via {@link #instance()} — it returns {@code null}
 * when libXcomposite is unavailable, so callers fall back to the on-window capture path.
 */
public interface XComposite extends Library {

    /** Redirect modes for XCompositeRedirectWindow (Xcomposite.h). */
    int CompositeRedirectAutomatic = 0;
    int CompositeRedirectManual = 1;

    /** True (non-zero) when the Composite extension is present on the display. */
    boolean XCompositeQueryExtension(Pointer display, IntByReference eventBase, IntByReference errorBase);

    /**
     * Allocates and returns a Pixmap XID that names {@code window}'s current off-screen contents. Only
     * valid when the window is redirected (which a running compositor does for every top-level window).
     * Free the returned pixmap with {@link X11#XFreePixmap}.
     */
    Pointer XCompositeNameWindowPixmap(Pointer display, Pointer window);

    /** Lazily-loaded singleton; {@code null} when libXcomposite could not be loaded. */
    static XComposite instance() {
        return Holder.INSTANCE;
    }

    /** Deferred, exception-safe load so a missing libXcomposite doesn't break window capture. */
    final class Holder {
        static final XComposite INSTANCE = load();

        private static XComposite load() {
            try {
                return Native.load("Xcomposite", XComposite.class);
            } catch (Throwable t) {
                return null;
            }
        }

        private Holder() {}
    }
}
