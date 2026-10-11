package com.botmaker.shared.opencv;

import java.awt.Dimension;

/**
 * Makes template matching resolution-independent so a bot's templates keep matching when the target
 * runs at a different screen resolution / DPI than the one they were captured at.
 *
 * <p>A template knows the capture resolution it was authored at (the SDK's bot screen size, or the picture's own
 * frame). At runtime the live capture may be a different size, so on-screen artwork is scaled by
 * {@code liveSize / authoredSize} relative to the template. {@link #primaryScale} is that ratio, the smaller of
 * the two axes' when the shapes differ; the matcher resizes the template by it before matching. {@link #fallbackScales} adds a small pyramid around the primary scale to absorb rounding
 * and DPI quirks — the matcher only pays for it on a miss.
 *
 * <p><b>The project-wide fallback is gone</b> (2026-09-22). A template with no authored size of its own used
 * to fall back to a project-wide default resolution, read from {@code capture.width} /
 * {@code capture.height}. Nothing in any module ever wrote those keys, so the fallback answered {@code null}
 * for every project that has ever existed and this method returned {@code 1.0} — exactly what it returns
 * now. The branch is deleted rather than kept, because a fallback that has never once been taken is a
 * statement about the code that is not true of the program.
 */
final class ResolutionScaler {

    /** Multipliers applied around the primary scale, tried in order, on a miss. */
    private static final double[] FALLBACK = {0.9, 1.1, 0.8, 1.2, 0.85, 1.15};
    /** Ratios within this share of each other are one aspect ratio: a border's rounding is not a shape. */
    private static final double SAME_SHAPE = 0.01;

    private ResolutionScaler() {}

    /**
     * The scale to resize a template by so it matches the live capture, from the template's own
     * {@code authored} capture resolution (recorded beside the picture when it was captured).
     *
     * <p>Returns {@code 1.0} (no scaling) when the template records none, or when the computed ratio is
     * implausible (e.g. a small cropped region vs. a full-screen authored resolution) so we never make
     * matching worse than pixel-exact behaviour.
     */
    static double primaryScale(int liveWidth, int liveHeight, Dimension authored) {
        if (authored == null || authored.width <= 0 || authored.height <= 0) {
            return 1.0;
        }
        double sx = liveWidth / (double) authored.width;
        double sy = liveHeight / (double) authored.height;
        // Another shape (a 16:9 picture on a 16:10 frame): a game fits its art by the smaller ratio and gives the
        // rest to the edges, so the mean would grow every picture past the art it shows (2026-10-11).
        double s = Math.abs(sx - sy) > SAME_SHAPE * Math.max(sx, sy) ? Math.min(sx, sy) : (sx + sy) / 2.0;
        if (s <= 0.2 || s >= 5.0) {
            return 1.0; // implausible ratio — treat as native scale
        }
        return s;
    }

    /** The pyramid of fallback scales around {@code primary}, tried only when the primary scale misses. */
    static double[] fallbackScales(double primary) {
        double[] out = new double[FALLBACK.length];
        for (int i = 0; i < FALLBACK.length; i++) {
            out[i] = primary * FALLBACK[i];
        }
        return out;
    }
}
