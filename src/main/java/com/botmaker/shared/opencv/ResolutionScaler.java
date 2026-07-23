package com.botmaker.shared.opencv;

import com.botmaker.shared.config.ProjectProperties;

import java.awt.Dimension;

/**
 * Makes template matching resolution-independent so a bot's templates keep matching when the target
 * runs at a different screen resolution / DPI than the one they were captured at.
 *
 * <p>Templates are authored at the project's <em>default capture resolution</em> (configured in
 * Studio, read via {@link ProjectProperties#defaultResolution()}). At runtime the live capture may be
 * a different size, so on-screen artwork is scaled by {@code liveSize / authoredSize} relative to the
 * template. {@link #primaryScale} is that ratio; the matcher resizes the template by it before
 * matching. {@link #fallbackScales} adds a small pyramid around the primary scale to absorb rounding
 * and DPI quirks — the matcher only pays for it on a miss.
 */
final class ResolutionScaler {

    /** Multipliers applied around the primary scale, tried in order, on a miss. */
    private static final double[] FALLBACK = {0.9, 1.1, 0.8, 1.2, 0.85, 1.15};

    private ResolutionScaler() {}

    /**
     * The scale to resize a template by so it matches the live capture, using the project-wide default
     * authored resolution ({@link ProjectProperties#defaultResolution()}).
     */
    static double primaryScale(int liveWidth, int liveHeight) {
        return primaryScale(liveWidth, liveHeight, null);
    }

    /**
     * The scale to resize a template by so it matches the live capture. Prefers the template's own
     * {@code authored} capture resolution (from its metadata sidecar) when provided, else falls back to
     * the project-wide {@link ProjectProperties#defaultResolution()}. Returns {@code 1.0} (no scaling) when
     * neither is configured, or when the computed ratio is implausible (e.g. a small cropped region vs. a
     * full-screen authored resolution) so we never make matching worse than pixel-exact behaviour.
     */
    static double primaryScale(int liveWidth, int liveHeight, Dimension authored) {
        if (authored == null || authored.width <= 0 || authored.height <= 0) {
            authored = ProjectProperties.defaultResolution();
        }
        if (authored == null || authored.width <= 0 || authored.height <= 0) {
            return 1.0;
        }
        double sx = liveWidth / (double) authored.width;
        double sy = liveHeight / (double) authored.height;
        double s = (sx + sy) / 2.0;
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
