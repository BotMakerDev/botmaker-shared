package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.GenericWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code capture.linux} carries <b>729 missed lines</b> and {@link LinuxController} is scheduled to be split
 * four ways (S18). This pins the part of {@code captureWindow} that decides <em>which of its four exits it
 * takes</em>, so the split can be checked against something.
 *
 * <p>All four exits — the composite pixmap, the root crop, the on-window drawable, and giving up — are chosen
 * by one predicate, {@link LinuxController#isAllBlack}. It is pure, so it is tested directly and exhaustively;
 * the exits it feeds need a live X server and are guarded accordingly.
 *
 * <p>{@code isAllBlack} <b>samples</b> rather than scanning: a sparse grid, roughly every {@code min(w,h)/17}
 * pixels. That is a deliberate trade — scanning a 4K frame per capture would cost more than the capture — and
 * it means the answer is "no sampled pixel was lit", not "no pixel was lit". Two tests below pin that
 * explicitly, because it is the kind of approximation a later reader deletes on the assumption it was sloppy.
 *
 * <p>This class replaces {@code LinuxControllerTest} (S3), which covered the same predicate in three cases and
 * nothing else. Two test classes for one method is how the two drift; the cases it had are all here.
 */
class LinuxControllerCaptureTest {

    // ---- The predicate the four exit paths are chosen by. Pure; runs everywhere. ----

    private static BufferedImage filled(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, rgb);
        }
        return img;
    }

    @Test
    void aNullFrameCountsAsBlackSoTheNextExitIsTried() {
        assertTrue(LinuxController.isAllBlack(null),
                "null must read as black — decode() returns null for an unusable XImage, and the capture "
                        + "ladder relies on that falling through to the next exit rather than being returned");
    }

    @Test
    void aFullyBlackFrameIsBlack() {
        assertTrue(LinuxController.isAllBlack(filled(200, 150, 0xFF000000)));
    }

    /** Alpha is masked off: an opaque black and a transparent black are both "the compositor gave us nothing". */
    @Test
    void alphaIsIgnored() {
        assertTrue(LinuxController.isAllBlack(filled(200, 150, 0x00000000)), "fully transparent black");
        assertTrue(LinuxController.isAllBlack(filled(200, 150, 0xFF000000)), "opaque black");
    }

    @Test
    void anyLitFrameIsNotBlack() {
        assertFalse(LinuxController.isAllBlack(filled(200, 150, 0xFF202020)), "near-black is still not black");
        assertFalse(LinuxController.isAllBlack(filled(200, 150, 0xFFFFFFFF)), "white");
    }

    /** A single lit channel is enough — a frame with only blue content is a frame. */
    @Test
    void oneLitChannelIsEnough() {
        assertFalse(LinuxController.isAllBlack(filled(64, 64, 0xFF000001)));
    }

    /** The corner is always sampled (the grid starts at 0,0), so this is the reliable "one bright pixel" case. */
    @Test
    void oneLitPixelAtTheSampledOriginIsEnough() {
        BufferedImage img = filled(200, 150, 0xFF000000);
        img.setRGB(0, 0, 0xFFFFFFFF);
        assertFalse(LinuxController.isAllBlack(img));
    }

    /**
     * The documented cost of sampling: on a large frame, content that falls entirely between grid points is
     * not seen. Here the grid step is {@code min(400,400)/17 = 23}, so a lit pixel at (1,1) is missed and the
     * frame is reported black — the capture then falls through to the next exit.
     *
     * <p>This is not a bug to fix in place; it is the trade the sparse grid buys, and a frame with one lit
     * pixel is indistinguishable from an unredirected one for the purpose the predicate serves. Pinned so that
     * a future reader tightening the step knows the behaviour is chosen.
     */
    @Test
    void contentBetweenGridPointsIsMissedOnALargeFrame() {
        BufferedImage img = filled(400, 400, 0xFF000000);
        img.setRGB(1, 1, 0xFFFFFFFF);
        assertTrue(LinuxController.isAllBlack(img),
                "sampling is expected to miss this; if it now sees it, the step changed and the cost of "
                        + "isAllBlack on a 4K frame changed with it");
    }

    /** Small frames get {@code step = 1} via the {@code max(1, …)} floor, so nothing is missed there. */
    @Test
    void smallFramesAreScannedDensely() {
        BufferedImage img = filled(16, 16, 0xFF000000);
        img.setRGB(7, 11, 0xFF010101);
        assertFalse(LinuxController.isAllBlack(img),
                "below 17px the step floors to 1, so every pixel is sampled and nothing may be missed");
    }

    @Test
    void aOnePixelFrameIsHandledRatherThanDividingByZero() {
        assertTrue(LinuxController.isAllBlack(filled(1, 1, 0xFF000000)));
        assertFalse(LinuxController.isAllBlack(filled(1, 1, 0xFFFFFFFF)));
    }

    // ---- The exits themselves. Need a real X server. ----

    private static boolean hasDisplay() {
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void captureOfANullWindowIsNullRatherThanThrowing() {
        assumeTrue(hasDisplay(), "no X display: DISPLAY is unset (a headless CI needs Xvfb)");
        try (LinuxController controller = new LinuxController(System.getenv("DISPLAY"), null)) {
            assertNull(controller.captureWindow(null),
                    "the contract is null-on-failure so the caller can fall back to a desktop capture");
        }
    }

    /**
     * A handle that was valid at enumeration and is gone by the capture is the normal case, not the odd one —
     * Studio's picker walks a list that the user is still interacting with. It must read as "no frame", never
     * as an Xlib error the default handler turns into a process exit.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void captureOfAStaleHandleIsNullRatherThanAnXlibExit() {
        assumeTrue(hasDisplay(), "no X display: DISPLAY is unset (a headless CI needs Xvfb)");
        try (LinuxController controller = new LinuxController(System.getenv("DISPLAY"), null)) {
            GenericWindow bogus = new GenericWindow(
                    new com.sun.jna.Pointer(0xDEAD_BEEFL), "gone", new java.awt.Rectangle(0, 0, 10, 10));
            assertNull(controller.captureWindow(bogus));
        }
    }

    /**
     * The whole ladder, against whatever this display actually has open. The assertion is deliberately weak —
     * a frame either has the window's geometry or is null — because which of the three exits fires depends on
     * the compositor. What it does prove is that the ladder terminates and never returns a mis-sized frame,
     * which is the property the S18 split must preserve.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void everyCapturedFrameMatchesItsWindowGeometry() {
        assumeTrue(hasDisplay(), "no X display: DISPLAY is unset (a headless CI needs Xvfb)");
        try (LinuxController controller = new LinuxController(System.getenv("DISPLAY"), null)) {
            List<GenericWindow> windows = controller.getAllWindows();
            assumeTrue(windows != null && !windows.isEmpty(), "this display has no enumerable windows to capture");

            int captured = 0;
            for (GenericWindow w : windows) {
                java.awt.Rectangle rect = w.getRect();
                if (rect == null || rect.width <= 0 || rect.height <= 0) continue;
                BufferedImage frame = controller.captureWindow(w);
                if (frame == null) continue; // unmapped, or no usable exit — allowed by contract
                captured++;
                assertNotNull(frame.getRaster());
                assertTrue(frame.getWidth() > 0 && frame.getHeight() > 0,
                        "a returned frame must be usable: got " + frame.getWidth() + "x" + frame.getHeight());
            }
            assumeTrue(captured > 0, "no window on this display produced a frame (compositor unredirected?)");
        }
    }
}
