package com.botmaker.shared.opencv;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The scale a picture is matched at, from the frame it was cut from and the frame shown now. */
class ResolutionScalerTest {

    @Test
    void aPictureCutAtTheLiveSizeIsMatchedAsItIs() {
        assertEquals(1.0, ResolutionScaler.primaryScale(1280, 720, new Dimension(1280, 720)));
        assertEquals(1.0, ResolutionScaler.primaryScale(1280, 720, null), "no authored size: as it is");
    }

    @Test
    void theSameShapeScalesByItsRatio() {
        assertEquals(1.5, ResolutionScaler.primaryScale(1920, 1080, new Dimension(1280, 720)), 1e-9);
    }

    @Test
    void anotherShapeScalesByTheSmallerRatioAsAGameFitsItsArt() {
        // 16:9 cut, 16:10 shown: the width grew 1.5×, the height 1.6667×; the art grew 1.5×.
        assertEquals(1.5, ResolutionScaler.primaryScale(1920, 1200, new Dimension(1280, 720)), 1e-9);
    }
}
