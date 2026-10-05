package com.botmaker.shared.capture.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * JNA bindings for the X Shape extension (libXext), used to give a window an <em>empty input region</em>: the
 * window still draws, but every pointer event, real or synthesized by XTest, goes to the window beneath it.
 *
 * <p>Loaded lazily and defensively via {@link #instance()}, which returns {@code null} when libXext is missing,
 * like {@link XComposite}.
 */
public interface XShape extends Library {

    /** Shape kinds (shape.h): the region that receives input. */
    int ShapeInput = 2;

    /** Shape operations (shape.h): replace the region. */
    int ShapeSet = 0;

    /** Rectangle ordering (X.h): no promise about the rectangles' order. */
    int Unsorted = 0;

    /** True when the Shape extension is present on the display. */
    boolean XShapeQueryExtension(Pointer display, IntByReference eventBase, IntByReference errorBase);

    /**
     * Sets {@code window}'s {@code destKind} region to the union of {@code rectangles}; {@code count} 0 with a
     * null array makes the region empty.
     */
    void XShapeCombineRectangles(Pointer display, Pointer window, int destKind, int xOffset, int yOffset,
                                 Pointer rectangles, int count, int op, int ordering);

    /** Lazily-loaded singleton; {@code null} when libXext could not be loaded. */
    static XShape instance() {
        return Holder.INSTANCE;
    }

    /** Deferred, exception-safe load so a missing libXext costs only click-through. */
    final class Holder {
        static final XShape INSTANCE = load();

        private static XShape load() {
            try {
                return Native.load("Xext", XShape.class);
            } catch (Throwable t) {
                return null;
            }
        }

        private Holder() {}
    }
}
