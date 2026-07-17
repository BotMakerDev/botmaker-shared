package com.botmaker.shared.ocr;

import java.awt.Rectangle;

/**
 * One recognized piece of text from {@link OcrEngine#recognize}. Immutable.
 *
 * <p>The {@link #bounds() bounding box} is in <b>source-local</b> coordinates (top-left of the
 * {@code BufferedImage} passed to {@code recognize} is {@code (0,0)}), already corrected for any
 * preprocessing upscale. shared has no SDK geometry types, so this uses {@link Rectangle}; the SDK's
 * {@code Text} facade maps it onto the SDK {@code Rect} and shifts to absolute screen coordinates.
 *
 * @param text       the recognized text (a single word or a whole line, per {@link #level()})
 * @param bounds     the source-local bounding box of the text
 * @param confidence Tesseract's confidence, 0..100 (higher is better)
 * @param level      whether this result is a {@link Level#WORD} or a {@link Level#LINE}
 */
public record TextResult(String text, Rectangle bounds, float confidence, Level level) {

    /** Granularity of a {@link TextResult}: an individual word or a whole line of text. */
    public enum Level { WORD, LINE }
}
