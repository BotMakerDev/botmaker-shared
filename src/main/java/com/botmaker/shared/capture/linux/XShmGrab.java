package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.FrameGrab;
import com.botmaker.shared.capture.FrameSink;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * A window's frames read as OBS reads them on X11 (2026-10-22): its composite pixmap named once and kept until the
 * window's size changes, read with {@code XShmGetImage} into one shared-memory segment the server writes straight
 * into, and turned into ARGB in the caller's own array. A frame costs two round trips and no allocation, where
 * {@link LinuxController#captureWindow} names the pixmap again, sends the pixels over the socket and builds an image.
 *
 * <p>The grab has an X connection of its own: Xlib is not thread-safe here (nothing calls {@code XInitThreads}), and
 * the controller's connection serves input and window lists on other threads. Closing the connection frees whatever
 * the server still holds for it. A frame it cannot read this way (no compositor, a black frame, a segment the server
 * refused) is read by {@code slow}, the controller's own capture.
 */
final class XShmGrab implements FrameGrab {

    private static final NativeLong ALL_PLANES = new NativeLong(X11.AllPlanes);
    /**
     * Frames in a row no shared read gave before the grab reads through {@code slow}, a refused segment or a window
     * hanging off the screen, trying shared memory again once every {@link #RETRY} frames.
     */
    private static final int FAILURES = 3;
    private static final int RETRY = 30;

    private final Pointer display;
    private final Pointer window;
    private final FrameGrab slow;
    private final XShm shm;
    private final XShm.Ipc ipc;
    /** Null when the server has no Composite: the window itself is read. */
    private final XComposite composite;
    private final X11.XWindowAttributes attributes = new X11.XWindowAttributes();

    /** The window's composite pixmap; null until named, and again when the window's size or mapping changed. */
    private Pointer pixmap;
    /**
     * Whether the pixmap is read, which a window covered by another still draws whole; false once it was refused, as
     * rootless XWayland refuses a window it redirects itself, until the size changes or a frame reads black.
     */
    private boolean throughPixmap;
    private X11.XImage image;
    private XShm.SegmentInfo segment;
    private int width;
    private int height;
    private int depth;
    private int failures;

    private XShmGrab(Pointer display, Pointer window, FrameGrab slow, XShm shm, XShm.Ipc ipc, XComposite composite) {
        this.display = display;
        this.window = window;
        this.slow = slow;
        this.shm = shm;
        this.ipc = ipc;
        this.composite = composite;
        this.throughPixmap = composite != null;
    }

    /**
     * A grab of {@code window} on {@code displayName} (null: {@code $DISPLAY}) through shared memory, or {@code slow}
     * itself when MIT-SHM is missing, as over the network.
     */
    static FrameGrab open(String displayName, Pointer window, FrameGrab slow) {
        XShm shm = XShm.instance();
        XShm.Ipc ipc = XShm.ipc();
        if (shm == null || ipc == null || window == null) return slow;
        X11ErrorTrap.install();
        Pointer display = X11.INSTANCE.XOpenDisplay(displayName);
        if (display == null) return slow;
        if (!shm.XShmQueryExtension(display)) {
            X11.INSTANCE.XCloseDisplay(display);
            return slow;
        }
        XComposite composite = XComposite.instance();
        if (composite != null && !composite.XCompositeQueryExtension(display, new IntByReference(), new IntByReference())) {
            composite = null;
        }
        // Kept composited once for the whole feed: a fullscreen game otherwise unredirects and blacks every pixmap.
        X11Utils.setKeepComposited(display, window);
        return new XShmGrab(display, window, slow, shm, ipc, composite);
    }

    @Override
    public boolean grab(FrameSink sink) {
        if (failures >= FAILURES && failures++ % RETRY != 0) return slow.grab(sink);
        if (X11.INSTANCE.XGetWindowAttributes(display, window, attributes) == 0
                || attributes.map_state != X11.IsViewable) {
            freePixmap();
            return false;
        }
        int w = attributes.width;
        int h = attributes.height;
        if (w <= 0 || h <= 0) return false;
        if (image == null || w != width || h != height || attributes.depth != depth) {
            freePixmap();
            freeSegment();
            throughPixmap = composite != null;
            if (!attached(w, h)) {
                failures++;
                return slow.grab(sink);
            }
        }
        if (throughPixmap && pixmap == null) pixmap = named();
        // The pixmap holds the window's border too; the frame starts inside it, where clicks are placed from.
        int border = attributes.border_width;
        boolean read = pixmap != null && shm.XShmGetImage(display, pixmap, image, border, border, ALL_PLANES);
        if (!read) {
            // No pixmap to read: the window's own pixels, where nothing covers it, as captureWindow's last rung.
            freePixmap();
            throughPixmap = false;
            read = shm.XShmGetImage(display, window, image, 0, 0, ALL_PLANES);
        }
        if (!read) {
            failures++;
            return slow.grab(sink);
        }
        failures = 0;
        int[] into = sink.pixels(w, h);
        argb(image.data, w, h, image.bytes_per_line, image.bits_per_pixel,
                image.red_mask.longValue(), image.green_mask.longValue(), image.blue_mask.longValue(), into);
        if (FrameSink.black(into, w, h)) {
            // Read while the compositor had it unredirected, or a pixmap gone stale: named again next frame.
            freePixmap();
            throughPixmap = composite != null;
            return slow.grab(sink);
        }
        return true;
    }

    /** The window's composite pixmap named now; null with no compositor running. */
    private Pointer named() {
        if (!LinuxController.compositorActive(display)) return null;
        Pointer named = composite.XCompositeNameWindowPixmap(display, window);
        return named == null || Pointer.nativeValue(named) == 0 ? null : named;
    }

    private boolean attached(int w, int h) {
        XShm.SegmentInfo info = new XShm.SegmentInfo();
        X11.XImage made = shm.XShmCreateImage(display, attributes.visual, attributes.depth, X11.ZPixmap, null,
                info, w, h);
        if (made == null) return false;
        if (made.bits_per_pixel < 24) {
            X11.INSTANCE.XFree(made.getPointer());
            return false;
        }
        int id = ipc.shmget(XShm.Ipc.IPC_PRIVATE, (long) made.bytes_per_line * made.height,
                XShm.Ipc.IPC_CREAT | XShm.Ipc.MODE_OWNER);
        if (id < 0) {
            X11.INSTANCE.XFree(made.getPointer());
            return false;
        }
        Pointer at = ipc.shmat(id, null, 0);
        if (at == null || Pointer.nativeValue(at) == -1) {
            ipc.shmctl(id, XShm.Ipc.IPC_RMID, null);
            X11.INSTANCE.XFree(made.getPointer());
            return false;
        }
        info.shmid = id;
        info.shmaddr = at;
        info.readOnly = false;
        made.data = at;
        boolean asked = shm.XShmAttach(display, info);
        X11.INSTANCE.XSync(display, false);
        // Marked for removal once attached on both sides: the kernel frees it when the last one lets go, even if
        // this process dies without closing.
        ipc.shmctl(id, XShm.Ipc.IPC_RMID, null);
        if (!asked) {
            ipc.shmdt(at);
            X11.INSTANCE.XFree(made.getPointer());
            return false;
        }
        segment = info;
        image = made;
        width = w;
        height = h;
        depth = attributes.depth;
        return true;
    }

    private void freePixmap() {
        if (pixmap == null) return;
        X11.INSTANCE.XFreePixmap(display, pixmap);
        pixmap = null;
    }

    private void freeSegment() {
        if (image == null) return;
        shm.XShmDetach(display, segment);
        X11.INSTANCE.XSync(display, false);
        ipc.shmdt(segment.shmaddr);
        // XFree, not the image's own destroy: the pixels are the segment's, not Xlib's to free.
        X11.INSTANCE.XFree(image.getPointer());
        image = null;
        segment = null;
    }

    @Override
    public void close() {
        freePixmap();
        freeSegment();
        X11.INSTANCE.XCloseDisplay(display);
    }

    /**
     * A ZPixmap's pixels at {@code data} as opaque ARGB into {@code into}, {@code width} a row: copied whole when they
     * are already 32-bit {@code xRGB}, the usual 24- and 32-bit visuals, else assembled through the masks. A mask of 0,
     * as a pixmap's image without a visual has, stands for the usual one.
     */
    static void argb(Pointer data, int width, int height, int stride, int bitsPerPixel,
                     long redMask, long greenMask, long blueMask, int[] into) {
        int red = redMask == 0 ? 0xFF0000 : (int) redMask;
        int green = greenMask == 0 ? 0x00FF00 : (int) greenMask;
        int blue = blueMask == 0 ? 0x0000FF : (int) blueMask;
        if (bitsPerPixel == 32 && red == 0xFF0000 && green == 0x00FF00 && blue == 0x0000FF) {
            if (stride == width * 4) {
                data.read(0, into, 0, width * height);
            } else {
                for (int y = 0; y < height; y++) data.read((long) y * stride, into, y * width, width);
            }
            FrameSink.opaque(into, width * height);
            return;
        }
        int bytes = bitsPerPixel / 8;
        int redShift = Integer.numberOfTrailingZeros(red);
        int greenShift = Integer.numberOfTrailingZeros(green);
        int blueShift = Integer.numberOfTrailingZeros(blue);
        byte[] row = new byte[stride];
        for (int y = 0; y < height; y++) {
            data.read((long) y * stride, row, 0, stride);
            for (int x = 0; x < width; x++) {
                int p = x * bytes;
                int pixel = (row[p] & 0xFF) | (row[p + 1] & 0xFF) << 8 | (row[p + 2] & 0xFF) << 16;
                if (bytes >= 4) pixel |= (row[p + 3] & 0xFF) << 24;
                into[y * width + x] = 0xFF000000 | ((pixel & red) >>> redShift) << 16
                        | ((pixel & green) >>> greenShift) << 8 | (pixel & blue) >>> blueShift;
            }
        }
    }
}
