package com.botmaker.shared.capture;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Logical-to-device screen rectangles, for one screen and several, at one scale and at mixed ones. */
class ScreenGeometryTest {

    @Test
    void anUnscaledScreenIsItsOwnDeviceRectangle() {
        ScreenGeometry.Screen screen = new ScreenGeometry.Screen(0, 0, 1920, 1080, 1, 1);
        assertEquals(new Rectangle(0, 0, 1920, 1080), screen.device());
        assertTrue(ScreenGeometry.unscaled(List.of(screen)));
    }

    /** {@code GDK_SCALE=2} on a 1920×1080 panel: AWT says 960×540, the grab and the clicks are 1920×1080. */
    @Test
    void aScaledScreenIsMultipliedBack() {
        ScreenGeometry.Screen screen = new ScreenGeometry.Screen(0, 0, 960, 540, 2, 2);
        assertEquals(new Rectangle(0, 0, 1920, 1080), screen.device());
        assertFalse(ScreenGeometry.unscaled(List.of(screen)));
    }

    @Test
    void twoScreensAtOneScaleStaySideBySide() {
        ScreenGeometry.Screen left = new ScreenGeometry.Screen(0, 0, 960, 540, 2, 2);
        ScreenGeometry.Screen right = new ScreenGeometry.Screen(960, 0, 960, 540, 2, 2);
        List<ScreenGeometry.Screen> screens = List.of(left, right);
        assertEquals(new Rectangle(0, 0, 3840, 1080), ScreenGeometry.deviceUnion(screens));
        assertEquals(new Rectangle(1920, 0, 1920, 1080), ScreenGeometry.inDesktopGrab(screens, right));
    }

    /**
     * Mixed scales: a 1920×1080 panel at 100% and a 3840×2160 one at 150% to its right. Each toolkit divides a
     * screen's device origin by that screen's own scale, so the right one reports x=1280 — and multiplying by
     * its own scale, not the first screen's, puts it back at 1920. The old crop used one scale for the offset.
     */
    @Test
    void mixedScalesUseEachScreensOwnScale() {
        ScreenGeometry.Screen left = new ScreenGeometry.Screen(0, 0, 1920, 1080, 1, 1);
        ScreenGeometry.Screen right = new ScreenGeometry.Screen(1280, 0, 2560, 1440, 1.5, 1.5);
        List<ScreenGeometry.Screen> screens = List.of(left, right);
        assertEquals(new Rectangle(1920, 0, 3840, 2160), right.device());
        assertEquals(new Rectangle(0, 0, 5760, 2160), ScreenGeometry.deviceUnion(screens));
        assertEquals(new Rectangle(1920, 0, 3840, 2160), ScreenGeometry.inDesktopGrab(screens, right));
    }

    @Test
    void aScreenLeftOfTheOriginShiftsTheGrab() {
        ScreenGeometry.Screen primary = new ScreenGeometry.Screen(0, 0, 1920, 1080, 1, 1);
        ScreenGeometry.Screen left = new ScreenGeometry.Screen(-1280, 0, 1280, 1024, 1, 1);
        List<ScreenGeometry.Screen> screens = List.of(primary, left);
        assertEquals(new Rectangle(1280, 0, 1920, 1080), ScreenGeometry.inDesktopGrab(screens, primary));
        assertEquals(new Rectangle(0, 0, 1280, 1024), ScreenGeometry.inDesktopGrab(screens, left));
    }

    @Test
    void aPieceIsTheLogicalGrabForTheDeviceRectangleAScreenShows() {
        ScreenGeometry.Screen right = new ScreenGeometry.Screen(1280, 0, 2560, 1440, 1.5, 1.5);
        ScreenGeometry.Piece piece = ScreenGeometry.piece(right, new Rectangle(0, 0, 5760, 2160));
        assertEquals(new Rectangle(1920, 0, 3840, 2160), piece.device());
        assertEquals(new Rectangle(1280, 0, 2560, 1440), piece.logical());
        assertNull(ScreenGeometry.piece(right, new Rectangle(0, 0, 1920, 1080)), "the left screen's area");
    }

    /** An odd device edge at scale 2 has no logical edge: the grab covers one more pixel, never a shifted one. */
    @Test
    void aPieceOnAnOddPixelCoversItWithoutShifting() {
        ScreenGeometry.Screen screen = new ScreenGeometry.Screen(0, 0, 960, 540, 2, 2);
        ScreenGeometry.Piece piece = ScreenGeometry.piece(screen, new Rectangle(101, 0, 400, 100));
        assertEquals(new Rectangle(50, 0, 201, 50), piece.logical());
        assertEquals(new Rectangle(100, 0, 402, 100), piece.device(), "the logical grab, scaled back exactly");
    }
}
