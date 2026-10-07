package com.botmaker.shared.capture.windows;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The geometry and pixel checks {@link WindowCapture} decides with, apart from the Win32 calls so they can be
 * checked on any OS.
 */
final class WindowFrames {

    private WindowFrames() {}

    /**
     * Where a window's client area sits in a frame of the window's visible bounds: {@code client}'s offset from
     * {@code frameBounds}' corner, clipped to the frame. Both rects are in screen pixels. {@code null} when they
     * don't overlap — a minimized window, or one whose frame came from before it moved.
     */
    static Rectangle clientWithin(Rectangle frameBounds, Rectangle client, int frameWidth, int frameHeight) {
        Rectangle inFrame = new Rectangle(client.x - frameBounds.x, client.y - frameBounds.y,
                client.width, client.height).intersection(new Rectangle(0, 0, frameWidth, frameHeight));
        return inFrame.isEmpty() ? null : inFrame;
    }

    /**
     * Whether every sampled pixel is black. A grid of about 17 by 17, never random: a dark game frame with a lit
     * corner must not read black one call and not the next.
     */
    static boolean isAllBlack(BufferedImage image) {
        if (image == null || image.getWidth() == 0 || image.getHeight() == 0) {
            return true;
        }
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 17);
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                if ((image.getRGB(x, y) & 0x00FFFFFF) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether {@code rect} covers one of {@code screens} to within 2 px — a fullscreen or borderless window. */
    static boolean coversAScreen(Rectangle rect, List<Rectangle> screens) {
        for (Rectangle screen : screens) {
            if (Math.abs(rect.x - screen.x) <= 2 && Math.abs(rect.y - screen.y) <= 2
                    && Math.abs(rect.width - screen.width) <= 2 && Math.abs(rect.height - screen.height) <= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * The points a screen copy of {@code rect} is checked at before it is trusted: the centre and a point just
     * inside each corner. If another window is on top at any of them, the copy would show that window.
     */
    static List<Point> probes(Rectangle rect) {
        int insetX = Math.min(8, Math.max(0, rect.width / 4));
        int insetY = Math.min(8, Math.max(0, rect.height / 4));
        int left = rect.x + insetX;
        int top = rect.y + insetY;
        int right = rect.x + rect.width - 1 - insetX;
        int bottom = rect.y + rect.height - 1 - insetY;
        return List.of(new Point(rect.x + rect.width / 2, rect.y + rect.height / 2),
                new Point(left, top), new Point(right, top), new Point(left, bottom), new Point(right, bottom));
    }
}
