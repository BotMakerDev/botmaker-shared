package com.botmaker.shared.capture.mac;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts between CoreGraphics' points and the pixels the rest of BotMaker speaks.
 *
 * <p>A window image is in pixels — twice its point size on a Retina display — and a bot adds a frame pixel to
 * the window's corner to get the screen coordinate it clicks. So every coordinate the controller hands out or
 * takes is a pixel, as on Windows ({@code WindowsDpi}), and this is the one place that knows the scale.
 *
 * <p>The pixel space: inside a display a point is {@code scale} pixels, and a display's pixel origin is its
 * point origin times the <em>main</em> display's scale. With one scale everywhere that is just
 * {@code point × scale}. With mixed scales (a Retina laptop and a 1× monitor beside it) displays that touch in
 * points still touch in pixels instead of overlapping: the monitor starts where the laptop's pixels end. A
 * frame pixel plus its window's corner reads back to the point it shows, on whichever display it is.
 */
public final class MacDisplays {

    /** One display: its bounds in points and its pixels per point. */
    record Display(Rectangle2D points, double scale) {
    }

    private static final long CACHE_MS = 1000;
    private static volatile MacDisplays cached;
    private static volatile long cachedAt;

    private final List<Display> displays;
    private final double mainScale;

    /** {@code displays} main first; an empty list behaves as one 1× display. */
    MacDisplays(List<Display> displays) {
        this.displays = List.copyOf(displays);
        this.mainScale = displays.isEmpty() ? 1 : displays.get(0).scale();
    }

    /**
     * The displays, read at most once a second: every gesture converts coordinates, and a display arrangement
     * changes far less often than a bot clicks.
     */
    public static MacDisplays current() {
        long now = System.currentTimeMillis();
        MacDisplays c = cached;
        if (c == null || now - cachedAt > CACHE_MS) {
            c = read();
            cached = c;
            cachedAt = now;
        }
        return c;
    }

    /** The displays now, main first (that is the order {@code CGGetActiveDisplayList} gives). */
    private static MacDisplays read() {
        CoreGraphics cg = CoreGraphics.INSTANCE;
        int[] ids = new int[16];
        IntByReference count = new IntByReference();
        if (cg.CGGetActiveDisplayList(ids.length, ids, count) != 0) {
            return new MacDisplays(List.of());
        }
        List<Display> found = new ArrayList<>();
        for (int i = 0; i < count.getValue(); i++) {
            CoreGraphics.CGRect b = cg.CGDisplayBounds(ids[i]);
            found.add(new Display(new Rectangle2D.Double(b.x, b.y, b.width, b.height), scaleOf(cg, ids[i])));
        }
        return new MacDisplays(found);
    }

    private static double scaleOf(CoreGraphics cg, int display) {
        Pointer mode = cg.CGDisplayCopyDisplayMode(display);
        if (mode == null) {
            return 1;
        }
        try {
            long points = cg.CGDisplayModeGetWidth(mode);
            long pixels = cg.CGDisplayModeGetPixelWidth(mode);
            return points > 0 && pixels > 0 ? (double) pixels / points : 1;
        } finally {
            cg.CGDisplayModeRelease(mode);
        }
    }

    private Rectangle2D pixelBounds(Display d) {
        Rectangle2D p = d.points();
        return new Rectangle2D.Double(p.getX() * mainScale, p.getY() * mainScale,
                p.getWidth() * d.scale(), p.getHeight() * d.scale());
    }

    private Display atPoint(double x, double y) {
        for (Display d : displays) {
            if (d.points().contains(x, y)) {
                return d;
            }
        }
        return displays.isEmpty() ? null : displays.get(0);
    }

    private Display atPixel(double x, double y) {
        for (Display d : displays) {
            if (pixelBounds(d).contains(x, y)) {
                return d;
            }
        }
        return displays.isEmpty() ? null : displays.get(0);
    }

    double scaleAtPoint(double x, double y) {
        Display d = atPoint(x, y);
        return d == null ? 1 : d.scale();
    }

    private Point2D toPixels2D(double x, double y, Display d) {
        if (d == null) {
            return new Point2D.Double(x, y);
        }
        Rectangle2D p = d.points();
        return new Point2D.Double(p.getX() * mainScale + (x - p.getX()) * d.scale(),
                p.getY() * mainScale + (y - p.getY()) * d.scale());
    }

    public Point toPixels(double x, double y) {
        Point2D px = toPixels2D(x, y, atPoint(x, y));
        return new Point((int) Math.floor(px.getX()), (int) Math.floor(px.getY()));
    }

    /** A window's bounds in pixels, scaled by the display its top-left corner is on. */
    Rectangle toPixels(Rectangle2D points) {
        Display d = atPoint(points.getX(), points.getY());
        double s = d == null ? 1 : d.scale();
        Point2D corner = toPixels2D(points.getX(), points.getY(), d);
        return new Rectangle((int) Math.floor(corner.getX()), (int) Math.floor(corner.getY()),
                (int) Math.round(points.getWidth() * s), (int) Math.round(points.getHeight() * s));
    }

    Point2D toPoints(double x, double y) {
        Display d = atPixel(x, y);
        if (d == null) {
            return new Point2D.Double(x, y);
        }
        Rectangle2D p = d.points();
        return new Point2D.Double(p.getX() + (x - p.getX() * mainScale) / d.scale(),
                p.getY() + (y - p.getY() * mainScale) / d.scale());
    }

    /** A length in pixels near pixel {@code (x, y)}, in points. */
    double toPoints(double length, double x, double y) {
        Display d = atPixel(x, y);
        return d == null ? length : length / d.scale();
    }
}
