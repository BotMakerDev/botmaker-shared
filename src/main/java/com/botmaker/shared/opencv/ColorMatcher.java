package com.botmaker.shared.opencv;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The colour-detection engine behind {@code api.vision.Pixel}.
 *
 * <p>Two independent notions of precision, deliberately kept separate:
 * <ul>
 *   <li><b>Colour precision</b> — {@code tolerance}, a <b>CIELAB ΔE76</b> distance from the target colour.
 *       Lab is perceptually near-uniform, so one tolerance behaves consistently across hues; plain RGB
 *       distance does not (a shadow reads as far as a hue change). Rough scale: ΔE≈2.3 is the
 *       just-noticeable difference, ~10 is "clearly the same colour family", ~25+ is loose.</li>
 *   <li><b>Location precision</b> — the caller-supplied search region (a
 *       {@code api.capture.CaptureSource} region) plus two quantity gates, {@code minArea} and
 *       {@code minCount}. {@code minArea} is the smallest connected blob that counts as a hit — what stops a
 *       single stray anti-aliased pixel from reporting a match. {@code minCount} is how many matching pixels
 *       the whole search has to see before any of it counts, clustered or not.</li>
 * </ul>
 *
 * <p><b>The two gates are not the same test and do not compose.</b> {@code minCount} is evaluated on the raw
 * mask, <em>before</em> clustering, and decides whether the search matched at all; {@code minArea} then
 * decides which blobs survive. So a frame of scattered specks can pass {@code minCount} and still return no
 * cluster large enough to keep, and the surviving blobs' areas can sum to less than {@code minCount}. Reading
 * {@code minCount} as "the total area of what comes back" is wrong at exactly the boundary where it matters,
 * and right everywhere else — which is why it is said here rather than left to be inferred.
 *
 * <p>Pipeline: BGR → float Lab → per-pixel ΔE → threshold → count gate → {@code connectedComponentsWithStats}
 * → drop blobs under {@code minArea} → order largest-first.
 */
public final class ColorMatcher {

    static { OpenCvNative.ensureLoaded(); }

    private ColorMatcher() {}

    /**
     * Finds connected clusters whose colour is within {@code tolerance} (CIELAB ΔE) of {@code target},
     * largest first. Never null.
     *
     * @param minArea  the smallest connected blob that survives, as an area in pixels
     * @param minCount how many matching pixels the frame must hold before any cluster is returned; {@code 0}
     *                 for no requirement. Checked on the mask, so it counts scattered pixels the area filter
     *                 would drop
     */
    public static List<RawColorMatch> findClusters(BufferedImage image, Color target, double tolerance,
                                                   int minArea, int minCount) {
        Mat bgr = null, mask = null;
        try {
            bgr = OpencvManager.bufferedImageToMat(image);
            mask = deltaEMask(bgr, target, tolerance);
            return clusters(mask, minArea, minCount);
        } finally {
            release(bgr, mask);
        }
    }

    /**
     * Finds connected clusters whose colour falls inside the inclusive RGB box [{@code low}, {@code high}],
     * largest first. This is a straight channel-wise range test (no Lab) — use it when you genuinely want a
     * band per channel rather than a distance from one colour.
     *
     * @param minArea  the smallest connected blob that survives, as an area in pixels
     * @param minCount how many matching pixels the frame must hold before any cluster is returned; {@code 0}
     *                 for no requirement
     */
    public static List<RawColorMatch> findClustersInRange(BufferedImage image, Color low, Color high,
                                                          int minArea, int minCount) {
        Mat bgr = null, mask = null;
        try {
            bgr = OpencvManager.bufferedImageToMat(image);
            mask = new Mat();
            // bufferedImageToMat yields BGR, so the scalar order is (blue, green, red).
            Scalar lo = new Scalar(Math.min(low.getBlue(), high.getBlue()),
                                   Math.min(low.getGreen(), high.getGreen()),
                                   Math.min(low.getRed(), high.getRed()));
            Scalar hi = new Scalar(Math.max(low.getBlue(), high.getBlue()),
                                   Math.max(low.getGreen(), high.getGreen()),
                                   Math.max(low.getRed(), high.getRed()));
            Core.inRange(bgr, lo, hi, mask);
            return clusters(mask, minArea, minCount);
        } finally {
            release(bgr, mask);
        }
    }

    /** The CIELAB ΔE76 distance between two colours — the same metric {@link #findClusters} thresholds on. */
    public static double deltaE(Color a, Color b) {
        double[] la = toLab(a), lb = toLab(b);
        double dl = la[0] - lb[0], da = la[1] - lb[1], db = la[2] - lb[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /** Fraction (0..1) of pixels within {@code tolerance} of {@code target}. */
    public static double coverage(BufferedImage image, Color target, double tolerance) {
        Tally t = tally(image, target, tolerance);
        return t.total() == 0 ? 0.0 : t.matching() / (double) t.total();
    }

    /**
     * How many pixels of {@code image} are within {@code tolerance} of {@code target} — the same number
     * {@link #coverage} divides, before it is turned into a fraction.
     *
     * <p>Both exist because both are wanted verbatim: a bot asking "how full is this health bar" wants the
     * fraction, and an editor showing what a {@code minCount} threshold would do to a frame wants the count
     * it will actually be compared against. Deriving one from the other at the call site means multiplying by
     * a pixel total the caller has to fetch and round, which is how the two drift.
     */
    public static int matchCount(BufferedImage image, Color target, double tolerance) {
        return tally(image, target, tolerance).matching();
    }

    /** Matching and total pixel counts of one ΔE mask — the single place either number is produced. */
    private record Tally(int matching, int total) {}

    private static Tally tally(BufferedImage image, Color target, double tolerance) {
        Mat bgr = null, mask = null;
        try {
            bgr = OpencvManager.bufferedImageToMat(image);
            mask = deltaEMask(bgr, target, tolerance);
            return new Tally(Core.countNonZero(mask), mask.rows() * mask.cols());
        } finally {
            release(bgr, mask);
        }
    }

    /**
     * Builds an 8-bit mask (255 = within tolerance) of per-pixel CIELAB ΔE76 from {@code target}.
     *
     * <p>The BGR image is converted to float [0,1] <em>before</em> {@code COLOR_BGR2Lab} so OpenCV emits true
     * Lab (L 0..100, a/b ≈ -127..127). Converting an 8-bit image instead would give L rescaled to 0..255 and
     * a/b offset by 128, and the resulting distances would not be ΔE.
     */
    private static Mat deltaEMask(Mat bgr, Color target, double tolerance) {
        Mat bgrF = new Mat(), lab = new Mat(), diff = new Mat(), dist = new Mat(), mask = new Mat();
        List<Mat> ch = new ArrayList<>();
        Mat sq0 = new Mat(), sq1 = new Mat(), sq2 = new Mat(), sum = new Mat();
        try {
            bgr.convertTo(bgrF, CvType.CV_32FC3, 1.0 / 255.0);
            Imgproc.cvtColor(bgrF, lab, Imgproc.COLOR_BGR2Lab);

            double[] t = toLab(target);
            Core.subtract(lab, new Scalar(t[0], t[1], t[2]), diff);
            Core.split(diff, ch);
            Core.multiply(ch.get(0), ch.get(0), sq0);
            Core.multiply(ch.get(1), ch.get(1), sq1);
            Core.multiply(ch.get(2), ch.get(2), sq2);
            Core.add(sq0, sq1, sum);
            Core.add(sum, sq2, sum);
            Core.sqrt(sum, dist);

            // dist <= tolerance  ->  255
            Imgproc.threshold(dist, dist, tolerance, 255, Imgproc.THRESH_BINARY_INV);
            dist.convertTo(mask, CvType.CV_8UC1);
            return mask;
        } finally {
            release(bgrF, lab, diff, dist, sq0, sq1, sq2, sum);
            for (Mat m : ch) m.release();
        }
    }

    /**
     * Connected components of a binary mask, filtered by {@code minArea}, largest first — but only once the
     * mask holds at least {@code minCount} matching pixels overall.
     *
     * <p>The count gate is deliberately applied to the mask rather than to the surviving clusters. "Is enough
     * of this colour on screen" is a question about the colour, not about how it happens to clump; a health
     * bar drawn as twenty separate segments answers it, and would fail a gate that only counted whole blobs.
     */
    private static List<RawColorMatch> clusters(Mat mask, int minArea, int minCount) {
        if (minCount > 0 && Core.countNonZero(mask) < minCount) return new ArrayList<>();
        Mat labels = new Mat(), stats = new Mat(), centroids = new Mat();
        try {
            int n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S);
            List<RawColorMatch> out = new ArrayList<>();
            // Label 0 is the background component — always skip it.
            for (int i = 1; i < n; i++) {
                int area = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
                if (area < Math.max(1, minArea)) continue;
                out.add(new RawColorMatch(
                        (int) stats.get(i, Imgproc.CC_STAT_LEFT)[0],
                        (int) stats.get(i, Imgproc.CC_STAT_TOP)[0],
                        (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0],
                        (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0],
                        area,
                        centroids.get(i, 0)[0],
                        centroids.get(i, 1)[0]));
            }
            out.sort(Comparator.comparingInt(RawColorMatch::pixelCount).reversed());
            return out;
        } finally {
            release(labels, stats, centroids);
        }
    }

    /** sRGB → CIELAB (D65), returning {L, a, b} with L in 0..100. */
    private static double[] toLab(Color c) {
        double r = invGamma(c.getRed() / 255.0);
        double g = invGamma(c.getGreen() / 255.0);
        double b = invGamma(c.getBlue() / 255.0);

        // sRGB → XYZ (D65)
        double x = r * 0.4124564 + g * 0.3575761 + b * 0.1804375;
        double y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750;
        double z = r * 0.0193339 + g * 0.1191920 + b * 0.9503041;

        // Normalise by the D65 white point
        double fx = labF(x / 0.95047);
        double fy = labF(y / 1.00000);
        double fz = labF(z / 1.08883);

        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double invGamma(double u) {
        return (u <= 0.04045) ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return (t > 0.008856) ? Math.cbrt(t) : (7.787 * t) + (16.0 / 116.0);
    }

    private static void release(Mat... mats) {
        for (Mat m : mats) if (m != null) m.release();
    }
}
