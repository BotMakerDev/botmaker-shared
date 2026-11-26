// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import com.botmaker.shared.capture.ToolCapture.Attempt;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A screenshot program's grab tried again, never the Robot, and a region cropped out of the desktop grab. */
class ToolCaptureTest {

    private static final BufferedImage IMAGE = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);

    @Test
    void aGrabThatWroteNothingIsTriedOnceMore() {
        AtomicInteger calls = new AtomicInteger();
        BufferedImage got = ToolCapture.firstImage(2, Duration.ZERO,
                () -> calls.incrementAndGet() == 1 ? Attempt.EMPTY : Attempt.of(IMAGE));
        assertSame(IMAGE, got);
        assertEquals(2, calls.get());
    }

    @Test
    void noImageAfterEveryTryIsNullNotAnotherBackend() {
        AtomicInteger calls = new AtomicInteger();
        assertNull(ToolCapture.firstImage(2, Duration.ZERO, () -> {
            calls.incrementAndGet();
            return Attempt.EMPTY;
        }));
        assertEquals(2, calls.get());
    }

    @Test
    void aGrabThatHungIsNotTriedAgain() {
        AtomicInteger calls = new AtomicInteger();
        assertNull(ToolCapture.firstImage(2, Duration.ZERO, () -> {
            calls.incrementAndGet();
            return Attempt.FAILED;
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void theFirstImageEndsTheTries() {
        AtomicInteger calls = new AtomicInteger();
        ToolCapture.firstImage(2, Duration.ZERO, () -> {
            calls.incrementAndGet();
            return Attempt.of(IMAGE);
        });
        assertEquals(1, calls.get());
    }

    @Test
    void aRegionIsCutFromTheDesktopGrabByTheVirtualDesktopsOrigin() {
        BufferedImage desktop = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        desktop.setRGB(150, 50, 0xFF0000);
        // A monitor left of the primary puts the virtual origin at -100.
        BufferedImage region = ScreenCapture.crop(desktop, new Rectangle(50, 50, 20, 10), new Rectangle(-100, 0, 300, 200));
        assertEquals(20, region.getWidth());
        assertEquals(10, region.getHeight());
        assertEquals(0xFF0000, region.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void aRegionPastTheGrabIsClampedToIt() {
        BufferedImage desktop = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        BufferedImage region = ScreenCapture.crop(desktop, new Rectangle(90, 90, 50, 50), new Rectangle(0, 0, 100, 100));
        assertEquals(10, region.getWidth());
        assertEquals(10, region.getHeight());
    }
}
