package com.botmaker.shared.capture.mac;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure point ↔ pixel arithmetic; runs on any OS, no CoreGraphics. */
class MacDisplaysTest {

    private static final MacDisplays.Display RETINA =
            new MacDisplays.Display(new Rectangle2D.Double(0, 0, 1512, 982), 2);
    /** A 1× monitor to the Retina laptop's right. */
    private static final MacDisplays.Display EXTERNAL =
            new MacDisplays.Display(new Rectangle2D.Double(1512, 0, 1920, 1080), 1);

    @Test
    void retinaDoublesEveryCoordinate() {
        MacDisplays displays = new MacDisplays(List.of(RETINA));
        assertEquals(new Point(200, 100), displays.toPixels(100, 50));
        assertEquals(new Rectangle(20, 40, 1600, 1200),
                displays.toPixels(new Rectangle2D.Double(10, 20, 800, 600)));
        assertEquals(new Point2D.Double(100, 50), displays.toPoints(200, 100));
    }

    @Test
    void aWindowsFramePixelRoundTripsToThePointItShows() {
        MacDisplays displays = new MacDisplays(List.of(RETINA));
        Rectangle window = displays.toPixels(new Rectangle2D.Double(300, 200, 640, 480));
        // A bot finds a template at frame pixel (50, 30) and clicks the window's corner plus that.
        Point2D clicked = displays.toPoints(window.x + 50, window.y + 30);
        assertEquals(new Point2D.Double(325, 215), clicked);
    }

    @Test
    void eachDisplayUsesItsOwnScale() {
        MacDisplays displays = new MacDisplays(List.of(RETINA, EXTERNAL));
        assertEquals(2, displays.scaleAtPoint(100, 100));
        assertEquals(1, displays.scaleAtPoint(2000, 100));
    }

    @Test
    void theMonitorBesideARetinaLaptopStartsWhereTheLaptopsPixelsEnd() {
        MacDisplays displays = new MacDisplays(List.of(RETINA, EXTERNAL));
        assertEquals(new Point(3024, 0), displays.toPixels(1512, 0));
        assertEquals(new Point(3512, 100), displays.toPixels(2000, 100));
        assertEquals(new Point2D.Double(2000, 100), displays.toPoints(3512, 100));
        assertEquals(new Point2D.Double(1000, 100), displays.toPoints(2000, 200),
                "a pixel inside the laptop's range is the laptop's");
    }

    @Test
    void aClickOnAWindowOnTheExternalMonitorRoundTrips() {
        MacDisplays displays = new MacDisplays(List.of(RETINA, EXTERNAL));
        Rectangle window = displays.toPixels(new Rectangle2D.Double(2000, 100, 640, 480));
        assertEquals(new Rectangle(3512, 100, 640, 480), window);
        assertEquals(new Point2D.Double(2050, 130), displays.toPoints(window.x + 50, window.y + 30));
    }

    @Test
    void aPointOffEveryDisplayTakesTheMainDisplaysScale() {
        MacDisplays displays = new MacDisplays(List.of(RETINA, EXTERNAL));
        assertEquals(2, displays.scaleAtPoint(-50, -50));
        assertEquals(1, new MacDisplays(List.of()).scaleAtPoint(10, 10));
    }

    @Test
    void lengthsConvertAtTheScaleWhereTheyAre() {
        MacDisplays displays = new MacDisplays(List.of(RETINA));
        assertEquals(400, displays.toPoints(800, 10, 10));
    }
}
