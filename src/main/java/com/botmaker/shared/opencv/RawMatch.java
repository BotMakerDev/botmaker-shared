package com.botmaker.shared.opencv;

/**
 * Lightweight internal match result produced by {@link OpencvManager}.
 *
 * <p>Holds only plain coordinates and a score — no {@code org.opencv} types — so it can cross the
 * internal/api boundary freely. The vision API maps it onto the public
 * {@code api.vision.MatchResult}.
 */
public record RawMatch(int x, int y, int width, int height, double score) {
}
