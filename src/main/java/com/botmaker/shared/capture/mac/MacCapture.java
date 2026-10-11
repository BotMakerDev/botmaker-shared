package com.botmaker.shared.capture.mac;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import java.awt.image.BufferedImage;

/**
 * One window's pixels, through {@code CGWindowListCreateImage}.
 *
 * <p>The image is the window's frame, title bar included and shadow excluded, at the display's full resolution:
 * the same rectangle, in pixels, as the window's rect. It is drawn into a bitmap of a known layout rather than
 * read from the image's own buffer, whose byte order and alpha placement vary by display and source.
 *
 * <p>The window need not be in front: macOS keeps every window's buffer, so an occluded window captures whole.
 * A minimized one does not.
 */
final class MacCapture {

    private MacCapture() {
    }

    /** The window's pixels, or {@code null} without Screen Recording or when macOS returns no image. */
    static BufferedImage capture(long windowId) {
        if (!MacPermission.SCREEN_RECORDING.check()) {
            return null;
        }
        CoreGraphics cg = CoreGraphics.INSTANCE;
        Pointer image = cg.CGWindowListCreateImage(CoreGraphics.CGRect.nullRect(),
                CoreGraphics.kCGWindowListOptionIncludingWindow, (int) windowId,
                CoreGraphics.kCGWindowImageBoundsIgnoreFraming | CoreGraphics.kCGWindowImageBestResolution);
        if (image == null) {
            return null;
        }
        try {
            return toBufferedImage(cg, image);
        } finally {
            cg.CGImageRelease(image);
        }
    }

    private static BufferedImage toBufferedImage(CoreGraphics cg, Pointer image) {
        int width = (int) cg.CGImageGetWidth(image);
        int height = (int) cg.CGImageGetHeight(image);
        if (width <= 1 || height <= 1) {
            return null;
        }
        Memory pixels = new Memory((long) width * height * 4);
        Pointer space = cg.CGColorSpaceCreateDeviceRGB();
        Pointer context = cg.CGBitmapContextCreate(pixels, width, height, 8, width * 4L, space,
                CoreGraphics.BITMAP_XRGB_LITTLE);
        cg.CGColorSpaceRelease(space);
        if (context == null) {
            return null;
        }
        try {
            cg.CGContextDrawImage(context, new CoreGraphics.CGRect.ByValue(0, 0, width, height), image);
        } finally {
            cg.CGContextRelease(context);
        }
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, width, height, pixels.getIntArray(0, width * height), 0, width);
        return out;
    }
}
