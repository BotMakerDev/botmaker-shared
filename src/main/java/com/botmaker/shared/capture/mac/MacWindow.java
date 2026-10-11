package com.botmaker.shared.capture.mac;

import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Optional;

/**
 * One entry of the CoreGraphics window list.
 *
 * @param id      the CGWindowID
 * @param pid     the owning process, which events are posted to in the background
 * @param name    the window's title; empty for another app's window until Screen Recording is granted
 * @param owner   the owning app's name
 * @param bounds  the window's frame in points, title bar included
 * @param layer   0 for ordinary windows; menus, the Dock and panels sit above it
 * @param alpha   0 for an invisible window
 */
record MacWindow(long id, int pid, String name, String owner, Rectangle2D bounds, int layer, double alpha) {

    /**
     * What the window is called: its own title, else its app's name. Many games title their one window after
     * themselves or leave it blank, and a blank title is also all another app's window shows before Screen
     * Recording is granted — a target picked by name must still find something.
     */
    String title() {
        return name != null && !name.isBlank() ? name : owner == null ? "" : owner;
    }

    /** An ordinary app window that can be seen, rather than a menu, a shadow, a status item or a 1×1 helper. */
    boolean isOrdinary() {
        return layer == 0 && alpha > 0 && bounds.getWidth() > 1 && bounds.getHeight() > 1;
    }

    /** The front-most of {@code frontToBack} whose bounds hold the point, as a click there would reach. */
    static Optional<MacWindow> topmostAt(List<MacWindow> frontToBack, double x, double y) {
        return frontToBack.stream().filter(w -> w.bounds().contains(x, y)).findFirst();
    }

    /** The stable part, kept as the {@code GenericWindow}'s handle: the bounds change as the window moves. */
    Ref ref() {
        return new Ref(id, pid);
    }

    record Ref(long id, int pid) {
    }
}
