package com.botmaker.shared.opencv;

/**
 * Lightweight internal colour-cluster result produced by {@link ColorMatcher}.
 *
 * <p>Holds only plain coordinates and a pixel count — no {@code org.opencv} types — so it can cross the
 * internal/api boundary freely, exactly like {@link RawMatch}. The vision API maps it onto the public
 * {@code api.vision.ColorMatch}.
 *
 * <p>{@code x}/{@code y}/{@code width}/{@code height} are the cluster's bounding box; {@code centroidX} and
 * {@code centroidY} are its centre of mass, which for a non-rectangular blob is a better click target than
 * the bbox centre.
 */
public record RawColorMatch(int x, int y, int width, int height, int pixelCount,
                            double centroidX, double centroidY) {
}
