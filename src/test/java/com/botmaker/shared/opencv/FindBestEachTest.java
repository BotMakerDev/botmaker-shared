package com.botmaker.shared.opencv;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Many pictures on one frame: the same answers as one {@link OpencvManager#findBest} each, in order. */
class FindBestEachTest {

    private static BufferedImage noise(int width, int height, long seed) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random rnd = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
        }
        return img;
    }

    @Test
    void eachSearchScoresAsFindBestDoesInOrder() {
        BufferedImage frame = noise(640, 400, 7);
        List<BufferedImage> pictures = List.of(noise(60, 30, 1), noise(40, 40, 2), noise(80, 20, 3));
        int[][] at = {{100, 50}, {500, 300}, {20, 360}};
        for (int i = 0; i < pictures.size(); i++) frame.getGraphics().drawImage(pictures.get(i), at[i][0], at[i][1], null);
        Mat background = OpencvManager.bufferedImageToMat(frame);
        List<Mat> templates = pictures.stream().map(OpencvManager::bufferedImageToMat).toList();
        try {
            List<OpencvManager.Search> searches = new ArrayList<>();
            for (Mat template : templates) searches.add(new OpencvManager.Search(template, null, null));
            List<RawMatch> each = OpencvManager.findBestEach(background, searches, false);

            assertEquals(templates.size(), each.size());
            for (int i = 0; i < templates.size(); i++) {
                RawMatch alone = OpencvManager.findBest(templates.get(i), background, false);
                assertEquals(alone, each.get(i), "search " + i);
                assertEquals(at[i][0], each.get(i).x());
                assertEquals(at[i][1], each.get(i).y());
            }
        } finally {
            background.release();
            templates.forEach(Mat::release);
        }
    }

    @Test
    void aSearchThatCannotBeMatchedCostsItsOwnAnswerOnly() {
        BufferedImage frame = noise(320, 200, 5);
        BufferedImage picture = noise(40, 20, 6);
        frame.getGraphics().drawImage(picture, 60, 70, null);
        Mat background = OpencvManager.bufferedImageToMat(frame);
        Mat template = OpencvManager.bufferedImageToMat(picture);
        Mat empty = new Mat();
        try {
            List<RawMatch> each = OpencvManager.findBestEach(background, Arrays.asList(
                    null, new OpencvManager.Search(empty, null, null), new OpencvManager.Search(null, null, null),
                    new OpencvManager.Search(template, null, null)), false);

            assertNull(each.get(0));
            assertNull(each.get(1));
            assertNull(each.get(2));
            assertEquals(60, each.get(3).x());
            assertEquals(70, each.get(3).y());
        } finally {
            background.release();
            template.release();
            empty.release();
        }
    }

    @Test
    void anAreaIsSearchedAloneAndItsMatchIsInTheFramesPixels() {
        BufferedImage frame = noise(640, 400, 9);
        BufferedImage picture = noise(50, 30, 4);
        frame.getGraphics().drawImage(picture, 300, 200, null);
        Mat background = OpencvManager.bufferedImageToMat(frame);
        Mat template = OpencvManager.bufferedImageToMat(picture);
        try {
            List<RawMatch> each = OpencvManager.findBestEach(background, List.of(
                    new OpencvManager.Search(template, null, new Rectangle(280, 180, 100, 80)),
                    new OpencvManager.Search(template, null, new Rectangle(0, 0, 200, 150)),
                    new OpencvManager.Search(template, null, new Rectangle(10, 10, 20, 20)),
                    new OpencvManager.Search(template, null, new Rectangle(900, 900, 50, 50))), false);

            assertNotNull(each.get(0));
            assertEquals(300, each.get(0).x());
            assertEquals(200, each.get(0).y());
            // Searched where it is not, it scores low: the area really bounds the search.
            assertNotNull(each.get(1));
            assertEquals(true, each.get(1).score() < 0.5, "outside its area, scored " + each.get(1).score());
            assertNull(each.get(2), "an area smaller than the picture");
            assertNull(each.get(3), "an area off the frame");
        } finally {
            background.release();
            template.release();
        }
    }
}
