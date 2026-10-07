package com.botmaker.shared.capture;

import java.awt.Rectangle;
import java.util.List;

/**
 * Where each monitor is in <b>device pixels</b> — the space a desktop grab is in and the one input lands in.
 *
 * <p>Toolkits answer in logical units: AWT under {@code GDK_SCALE=2} (what KDE sets for 200% on X11) says a
 * 1920×1080 screen is 960×540, and Robot then grabs it at 960×540, while XTEST clicks in the 1920×1080 the
 * X server has. A template matched at {@code (x, y)} of such a grab was clicked at half the distance. Every
 * desktop rectangle is converted here, one way: <b>a screen's device rectangle is its logical rectangle times
 * its own scale</b>. AWT derives a screen's logical origin by dividing its device origin by that screen's
 * scale, so multiplying back is exact, mixed scales included. On Linux every screen shares one scale, which
 * makes JavaFX's bounds exact too; JavaFX on a mixed-scale Windows desktop lays its screens out its own way,
 * unmeasured.
 *
 * <p>Pure: the toolkit adapters build {@link Screen}s, everything else is arithmetic, unit-tested without a
 * display.
 */
public final class ScreenGeometry {

    private ScreenGeometry() {
    }

    /** One monitor as a toolkit reports it: logical bounds and the scale from logical to device pixels. */
    public record Screen(double x, double y, double width, double height, double scaleX, double scaleY) {

        /** Its rectangle in device pixels, rounded where the fraction is created. */
        public Rectangle device() {
            int left = (int) Math.round(x * scaleX);
            int top = (int) Math.round(y * scaleY);
            return new Rectangle(left, top, (int) Math.round((x + width) * scaleX) - left,
                (int) Math.round((y + height) * scaleY) - top);
        }

        /** Whether every scale is 1, so logical and device pixels are the same numbers. */
        boolean unscaled() {
            return scaleX == 1 && scaleY == 1;
        }
    }

    /** The union of every screen's device rectangle; an empty rectangle for no screens. */
    public static Rectangle deviceUnion(List<Screen> screens) {
        Rectangle union = null;
        for (Screen s : screens) {
            union = union == null ? s.device() : union.union(s.device());
        }
        return union == null ? new Rectangle() : union;
    }

    /**
     * Where {@code target} sits inside a grab of the whole desktop — its device rectangle relative to the
     * device union's top-left, which is where a desktop grab's {@code (0, 0)} is.
     */
    public static Rectangle inDesktopGrab(List<Screen> screens, Screen target) {
        Rectangle union = deviceUnion(screens);
        Rectangle device = target.device();
        return new Rectangle(device.x - union.x, device.y - union.y, device.width, device.height);
    }

    /** Whether no screen is scaled: a logical desktop grab is then already in device pixels. */
    public static boolean unscaled(List<Screen> screens) {
        return screens.stream().allMatch(Screen::unscaled);
    }

    /**
     * The part of the device rectangle {@code wanted} that {@code screen} shows, as the logical rectangle to
     * ask the toolkit for and the device rectangle that grab covers — or {@code null} when the screen shows
     * none of it.
     *
     * <p>The logical rectangle is the smallest one covering the device part, and the device rectangle is that
     * logical one scaled back, not the part itself: a device edge on an odd pixel at scale 2 has no logical
     * edge, and drawing the grab into the part would stretch or shift it by a pixel. The device rectangle may
     * so overhang {@code wanted} by less than a logical pixel; the caller's canvas clips it.
     */
    public static Piece piece(Screen screen, Rectangle wanted) {
        Rectangle shown = screen.device().intersection(wanted);
        if (shown.isEmpty()) {
            return null;
        }
        int left = (int) Math.floor(shown.x / screen.scaleX());
        int top = (int) Math.floor(shown.y / screen.scaleY());
        int right = (int) Math.ceil((shown.x + shown.width) / screen.scaleX());
        int bottom = (int) Math.ceil((shown.y + shown.height) / screen.scaleY());
        Rectangle logical = new Rectangle(left, top, right - left, bottom - top);
        return new Piece(logical, new Screen(left, top, right - left, bottom - top,
            screen.scaleX(), screen.scaleY()).device());
    }

    /**
     * {@code desktop} — a grab of every screen — cropped to {@code target}, clamped to the image. The one crop
     * both editors' screen choosers use.
     */
    public static java.awt.image.BufferedImage crop(java.awt.image.BufferedImage desktop, List<Screen> screens,
                                                    Screen target) {
        Rectangle r = inDesktopGrab(screens, target);
        int x = Math.max(0, Math.min(r.x, desktop.getWidth() - 1));
        int y = Math.max(0, Math.min(r.y, desktop.getHeight() - 1));
        int w = Math.max(1, Math.min(r.width, desktop.getWidth() - x));
        int h = Math.max(1, Math.min(r.height, desktop.getHeight() - y));
        return desktop.getSubimage(x, y, w, h);
    }

    /** A logical rectangle to grab and the device rectangle the grab is drawn into. */
    public record Piece(Rectangle logical, Rectangle device) {
    }
}
