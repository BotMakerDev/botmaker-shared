package com.botmaker.shared.opencv;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-pixel half of the colour search: exactly the pixels findClusters would count. */
class ColorMatcherMaskTest {

    @Test
    void theMaskMarksThePixelsWithinTolerance() {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, new Color(200, 50, 50).getRGB());
        image.setRGB(1, 0, new Color(205, 52, 48).getRGB());
        image.setRGB(2, 0, new Color(20, 20, 220).getRGB());

        boolean[] mask = ColorMatcher.matchMask(image, new Color(200, 50, 50), 5);

        assertArrayEquals(new boolean[]{true, true, false}, mask);
    }

    @Test
    void theMaskAgreesWithTheCount() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) image.setRGB(x, y, (x + y) % 2 == 0 ? 0xC83232 : 0x1414DC);
        }
        Color red = new Color(0xC83232);
        int marked = 0;
        for (boolean b : ColorMatcher.matchMask(image, red, 10)) if (b) marked++;

        assertEquals(ColorMatcher.matchCount(image, red, 10), marked);
    }
}
