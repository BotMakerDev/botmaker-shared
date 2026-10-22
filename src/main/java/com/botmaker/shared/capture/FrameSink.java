package com.botmaker.shared.capture;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;

/**
 * Where a {@link FrameGrab} writes a frame: an array the caller keeps and reuses, so a live feed allocates nothing per
 * frame (2026-10-22). The grab asks for the frame's size once, then writes its pixels row after row, as opaque ARGB.
 */
@FunctionalInterface
public interface FrameSink {

    /** An array of at least {@code width × height} ints the next frame is written into; called once per frame. */
    int[] pixels(int width, int height);

    /**
     * {@code frame} written into {@code sink} as opaque ARGB: the raster's own ints copied whole when it holds them so,
     * else read through its colour model. For a capture that only makes a {@code BufferedImage}.
     */
    static void write(BufferedImage frame, FrameSink sink) {
        int w = frame.getWidth();
        int h = frame.getHeight();
        int[] into = sink.pixels(w, h);
        int type = frame.getType();
        if ((type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB)
                && frame.getRaster().getDataBuffer() instanceof DataBufferInt ints
                && ints.getNumBanks() == 1 && ints.getOffset() == 0 && frame.getRaster().getParent() == null
                && frame.getSampleModel() instanceof SinglePixelPackedSampleModel packed
                && packed.getScanlineStride() == w) {
            System.arraycopy(ints.getData(), 0, into, 0, w * h);
        } else {
            frame.getRGB(0, 0, w, h, into, 0, w);
        }
        opaque(into, w * h);
    }

    /** The first {@code count} pixels of {@code pixels} made opaque: a game's frame has no transparency to keep. */
    static void opaque(int[] pixels, int count) {
        for (int i = 0; i < count; i++) pixels[i] |= 0xFF000000;
    }

    /** Whether a sparse grid of the frame's pixels, about 17 a side, is all black: a frame read while none was drawn. */
    static boolean black(int[] pixels, int width, int height) {
        int step = Math.max(1, Math.min(width, height) / 17);
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                if ((pixels[y * width + x] & 0x00FFFFFF) != 0) return false;
            }
        }
        return true;
    }
}
