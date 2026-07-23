package com.botmaker.shared.opencv;

import com.botmaker.shared.Diag;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED;
import static org.opencv.imgproc.Imgproc.TM_CCORR_NORMED;
import static org.opencv.imgproc.Imgproc.matchTemplate;

/**
 * Template-matching engine. Operates directly on OpenCV {@link Mat}s and returns plain
 * {@link RawMatch} records (no OpenCV types leak out). The native library is guaranteed loaded by
 * the static initializer.
 */
public final class OpencvManager {

    static { OpenCvNative.ensureLoaded(); }

    private static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.8;
    private static final double DEFAULT_OVERLAP_THRESHOLD = 0.5;

    private OpencvManager() {}

    // --- Conversion ------------------------------------------------------------------------------

    public static Mat bufferedImageToMat(BufferedImage image) {
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        converted.getGraphics().drawImage(image, 0, 0, null);

        Mat mat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
        byte[] pixels = ((DataBufferByte) converted.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, pixels);
        return mat;
    }

    private static boolean isRGBA(Mat mat) { return mat.channels() == 4; }
    private static boolean isRGB(Mat mat)  { return mat.channels() == 3; }
    private static boolean isGray(Mat mat) { return mat.channels() == 1; }

    /** Normalises {@code mat} in place to either 3-channel BGR or single-channel gray. */
    private static void normalise(Mat mat, boolean grayscale) {
        if (grayscale) {
            if (isRGB(mat))       Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY);
            else if (isRGBA(mat)) Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY);
        } else {
            if (isGray(mat))      Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2RGB);
            else if (isRGBA(mat)) Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB);
        }
    }

    /**
     * The alpha channel of a 4-channel (BGRA) template as a single-channel 8U mask (transparent pixels = 0),
     * or {@code null} when {@code mat} has no alpha. Must be called <em>before</em> {@link #normalise} flattens
     * the template. The caller owns and releases the returned Mat.
     */
    private static Mat extractAlphaMask(Mat mat) {
        if (!isRGBA(mat)) return null;
        java.util.List<Mat> channels = new ArrayList<>();
        Core.split(mat, channels);
        Mat alpha = channels.get(3);           // keep alpha as the mask
        for (int i = 0; i < 3; i++) channels.get(i).release();
        return alpha;
    }

    /**
     * Runs {@code matchTemplate} into {@code result}, normalising {@code localTemplate} in place first. When the
     * template carries an alpha channel (a transparent-background template), its alpha is used as a
     * <b>mask</b> with {@code TM_CCORR_NORMED} (the reliably mask-supporting normed method) so transparent
     * pixels are ignored; opaque templates use the standard {@code TM_CCOEFF_NORMED}. {@code localBackground}
     * must already be normalised to the same channel space.
     */
    private static void runMatch(Mat localBackground, Mat localTemplate, Mat result, boolean grayscale) {
        Mat mask = extractAlphaMask(localTemplate);
        try {
            normalise(localTemplate, grayscale);
            if (mask != null) {
                matchTemplate(localBackground, localTemplate, result, TM_CCORR_NORMED, mask);
            } else {
                matchTemplate(localBackground, localTemplate, result, TM_CCOEFF_NORMED);
            }
        } finally {
            if (mask != null) mask.release();
        }
    }

    // --- Matching --------------------------------------------------------------------------------

    public static RawMatch findBestMatch(Mat template, Mat background, boolean grayscale) {
        return findBestMatch(template, background, grayscale, DEFAULT_CONFIDENCE_THRESHOLD);
    }

    /**
     * Returns the single best match of {@code template} within {@code background} whose score meets
     * {@code confidenceThreshold}, or {@code null} if none qualifies.
     *
     * <p>Resolution-independent: the template is first resized by the project's
     * {@link ResolutionScaler#primaryScale primary scale}. If that misses the threshold, a small
     * pyramid of {@link ResolutionScaler#fallbackScales fallback scales} is tried (only on a miss),
     * so templates keep matching across different screen resolutions / DPI.
     */
    public static RawMatch findBestMatch(Mat template, Mat background, boolean grayscale, double confidenceThreshold) {
        return findBestMatch(template, background, grayscale, confidenceThreshold, null);
    }

    /**
     * As {@link #findBestMatch(Mat, Mat, boolean, double)} but scaling the template by the given
     * per-template {@code authored} capture resolution when non-null (falling back to the project default).
     */
    public static RawMatch findBestMatch(Mat template, Mat background, boolean grayscale,
                                         double confidenceThreshold, Dimension authored) {
        double primary = ResolutionScaler.primaryScale(background.width(), background.height(), authored);

        RawMatch best = matchScaled(template, background, grayscale, primary);
        if (best != null && best.score() >= confidenceThreshold) {
            return best;
        }
        // Miss at the primary scale — walk the fallback pyramid, keeping the best, early-out on a hit.
        for (double scale : ResolutionScaler.fallbackScales(primary)) {
            RawMatch candidate = matchScaled(template, background, grayscale, scale);
            if (candidate != null && (best == null || candidate.score() > best.score())) {
                best = candidate;
            }
            if (best != null && best.score() >= confidenceThreshold) {
                return best;
            }
        }
        return (best != null && best.score() >= confidenceThreshold) ? best : null;
    }

    /**
     * Returns the single best match of {@code template} within {@code background} <em>regardless</em> of any
     * confidence threshold ({@code score} is the raw {@code TM_CCOEFF_NORMED} peak), or {@code null} only when
     * the template can't fit the background. Callers that need a threshold gate use {@link #findBestMatch};
     * telemetry uses this so a miss can still report the real near-miss score instead of zero.
     *
     * <p>Applies the project's {@link ResolutionScaler#primaryScale primary scale} (single scale, no
     * pyramid) so the reported near-miss score reflects the resolution-corrected template.
     */
    public static RawMatch findBest(Mat template, Mat background, boolean grayscale) {
        return findBest(template, background, grayscale, null);
    }

    /**
     * As {@link #findBest(Mat, Mat, boolean)} but scaling the template by the given per-template
     * {@code authored} capture resolution when non-null (falling back to the project default).
     */
    public static RawMatch findBest(Mat template, Mat background, boolean grayscale,
                                    Dimension authored) {
        double primary = ResolutionScaler.primaryScale(background.width(), background.height(), authored);
        return matchScaled(template, background, grayscale, primary);
    }

    /**
     * Single-scale core: resize {@code template} by {@code scale} (1.0 = native) and return its best
     * location within {@code background} in background-pixel coordinates, with {@code width}/{@code
     * height} equal to the scaled (on-screen) template size. Returns {@code null} when the scaled
     * template cannot fit the background.
     */
    private static RawMatch matchScaled(Mat template, Mat background, boolean grayscale, double scale) {
        Mat localTemplate = resizeTemplate(template, scale);
        Mat localBackground = background.clone();
        Mat resultMat = new Mat();
        try {
            normalise(localBackground, grayscale);

            if (localBackground.width() < localTemplate.width() || localBackground.height() < localTemplate.height()) {
                return null;
            }

            runMatch(localBackground, localTemplate, resultMat, grayscale);
            Core.MinMaxLocResult mmr = Core.minMaxLoc(resultMat);
            Point loc = mmr.maxLoc;
            return new RawMatch((int) loc.x, (int) loc.y, localTemplate.cols(), localTemplate.rows(), mmr.maxVal);
        } finally {
            localTemplate.release();
            localBackground.release();
            resultMat.release();
        }
    }

    /** A clone of {@code template} resized by {@code scale}; an unscaled clone when scale ≈ 1. */
    private static Mat resizeTemplate(Mat template, double scale) {
        if (Math.abs(scale - 1.0) < 1e-3) {
            return template.clone();
        }
        int w = Math.max(1, (int) Math.round(template.cols() * scale));
        int h = Math.max(1, (int) Math.round(template.rows() * scale));
        Mat resized = new Mat();
        Imgproc.resize(template, resized, new org.opencv.core.Size(w, h), 0, 0,
                scale < 1.0 ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
        return resized;
    }

    /**
     * Best match score of {@code template} within a small window around the top-left location
     * {@code (x, y)} in {@code background}. Used by the compare API to measure how well a competing
     * template matches at a spot another template already matched — on the same captured frame, so
     * two visually-similar templates can be scored against each other without a second capture.
     *
     * <p>The window is the template footprint padded by {@code pad} pixels on each side (clamped to
     * the background), giving {@code matchTemplate} a little slack for sub-pixel offset. Returns
     * {@code -1.0} if the (clamped) window is smaller than the template.
     */
    public static double scoreAround(Mat template, Mat background, boolean grayscale, int x, int y, int pad) {
        Mat localTemplate = template.clone();
        Mat localBackground = background.clone();
        Mat window = null;
        Mat resultMat = new Mat();
        try {
            normalise(localBackground, grayscale);

            int tw = localTemplate.cols();      // channel count doesn't affect dimensions
            int th = localTemplate.rows();
            int x0 = Math.max(0, x - pad);
            int y0 = Math.max(0, y - pad);
            int x1 = Math.min(localBackground.cols(), x + tw + pad);
            int y1 = Math.min(localBackground.rows(), y + th + pad);
            if (x1 - x0 < tw || y1 - y0 < th) {
                return -1.0;
            }

            window = localBackground.submat(new org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0));
            runMatch(window, localTemplate, resultMat, grayscale);
            return Core.minMaxLoc(resultMat).maxVal;
        } finally {
            localTemplate.release();
            if (window != null) window.release();
            localBackground.release();
            resultMat.release();
        }
    }

    public static List<RawMatch> findMultipleMatches(Mat template, Mat background, boolean grayscale) {
        return findMultipleMatches(template, background, grayscale, DEFAULT_CONFIDENCE_THRESHOLD, DEFAULT_OVERLAP_THRESHOLD);
    }

    public static List<RawMatch> findMultipleMatches(Mat template, Mat background, boolean grayscale, double confidenceThreshold) {
        return findMultipleMatches(template, background, grayscale, confidenceThreshold, DEFAULT_OVERLAP_THRESHOLD, null);
    }

    /**
     * As {@link #findMultipleMatches(Mat, Mat, boolean, double)} but scaling the template by the given
     * per-template {@code authored} capture resolution when non-null (falling back to the project default).
     */
    public static List<RawMatch> findMultipleMatches(Mat template, Mat background, boolean grayscale,
                                                     double confidenceThreshold, Dimension authored) {
        return findMultipleMatches(template, background, grayscale, confidenceThreshold, DEFAULT_OVERLAP_THRESHOLD, authored);
    }

    public static List<RawMatch> findMultipleMatches(Mat template, Mat background, boolean grayscale,
                                                     double confidenceThreshold, double overlapThreshold) {
        return findMultipleMatches(template, background, grayscale, confidenceThreshold, overlapThreshold, null);
    }

    /**
     * Returns every non-overlapping match (via non-maximal suppression) at or above
     * {@code confidenceThreshold}.
     */
    public static List<RawMatch> findMultipleMatches(Mat template, Mat background, boolean grayscale,
                                                     double confidenceThreshold, double overlapThreshold,
                                                     Dimension authored) {
        if (template.empty() || background.empty()) {
            Diag.error("Error: Invalid input images for findMultipleMatches.");
            return new ArrayList<>();
        }

        // Resolution-independent: match the template at its primary scale (single scale here
        // to keep non-maximal suppression across a single template footprint tractable).
        double scale = ResolutionScaler.primaryScale(background.width(), background.height(), authored);
        Mat localTemplate = resizeTemplate(template, scale);
        Mat localBackground = background.clone();
        if (localBackground.width() < localTemplate.width() || localBackground.height() < localTemplate.height()) {
            Diag.error("Error: Template dimensions are larger than the background image.");
            localTemplate.release();
            localBackground.release();
            return new ArrayList<>();
        }
        Mat resultMat = new Mat();
        try {
            normalise(localBackground, grayscale);

            int w = localTemplate.cols();
            int h = localTemplate.rows();
            runMatch(localBackground, localTemplate, resultMat, grayscale);

            List<RawMatch> candidates = new ArrayList<>();
            for (int y = 0; y < resultMat.rows(); y++) {
                for (int x = 0; x < resultMat.cols(); x++) {
                    double score = resultMat.get(y, x)[0];
                    if (score >= confidenceThreshold) {
                        candidates.add(new RawMatch(x, y, w, h, score));
                    }
                }
            }
            if (candidates.isEmpty()) {
                return candidates;
            }

            // Non-maximal suppression: keep highest-scoring, drop overlapping competitors.
            candidates.sort(Comparator.comparingDouble(RawMatch::score).reversed());
            List<RawMatch> winners = new ArrayList<>();
            while (!candidates.isEmpty()) {
                RawMatch champion = candidates.removeFirst();
                winners.add(champion);
                candidates.removeIf(c -> intersectionOverUnion(champion, c) > overlapThreshold);
            }
            return winners;
        } finally {
            localTemplate.release();
            localBackground.release();
            resultMat.release();
        }
    }

    private static double intersectionOverUnion(RawMatch a, RawMatch b) {
        int xA = Math.max(a.x(), b.x());
        int yA = Math.max(a.y(), b.y());
        int xB = Math.min(a.x() + a.width(), b.x() + b.width());
        int yB = Math.min(a.y() + a.height(), b.y() + b.height());

        int intersection = Math.max(0, xB - xA) * Math.max(0, yB - yA);
        double union = (double) a.width() * a.height() + (double) b.width() * b.height() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }
}
