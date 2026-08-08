package com.botmaker.shared.emulator;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Decoder for {@code screencap}'s <b>raw</b> output — the framebuffer as Android handed it over, with no PNG
 * encode in between.
 *
 * <p>{@code screencap -p} asks the device to deflate a full frame before a single byte moves; that encode is
 * the largest fixed cost in the emulator capture path and it buys only bandwidth. Dropping {@code -p} gives a
 * tiny header followed by the pixels, which is both cheaper on the device and <b>exactly</b> the pixels the
 * compositor had. Whether it is cheaper <em>overall</em> depends on the wire — see {@link AdbEndpoint#local()},
 * which is what decides.
 *
 * <h2>The format, and why the length is the checksum</h2>
 *
 * The header is little-endian 32-bit words: width, height, pixel format — and, from API 29, a fourth
 * colorspace word. Nothing in the stream announces which of those two layouts it is, and there is no version
 * field or magic number to read. Rather than guess from an API level we would have to ask for separately (and
 * that emulators misreport), both layouts are <em>tried</em>: the pixel data is exactly
 * {@code width × height × bytesPerPixel} with no row padding, so only the correct header size makes the
 * payload length come out exact. A frame that satisfies that arithmetic has been validated far more strongly
 * than a magic number would manage; anything else returns {@code null} and the caller falls back to PNG.
 *
 * <p>Pure and static so it is testable without a device: {@link #decode(byte[])} is the whole contract, and
 * every failure is a {@code null} rather than an exception, matching the best-effort shape of everything else
 * in this package.
 */
final class RawFramebuffer {

    private RawFramebuffer() {
    }

    /** The two header layouts: {@code w,h,format} and, from API 29, {@code w,h,format,colorspace}. */
    private static final int HEADER_LEGACY = 12;
    private static final int HEADER_WITH_COLORSPACE = 16;

    /**
     * A ceiling on a plausible dimension, so garbage on the stream (a {@code screencap} that printed an error,
     * a truncated read) is rejected before it is used to size an allocation.
     */
    private static final int MAX_DIMENSION = 20_000;

    // android/graphics/PixelFormat + system/graphics.h — the formats screencap actually emits.
    private static final int RGBA_8888 = 1;
    private static final int RGBX_8888 = 2;
    private static final int RGB_888 = 3;
    private static final int RGB_565 = 4;
    private static final int BGRA_8888 = 5;

    /**
     * Decodes a raw {@code screencap} payload, or returns {@code null} if these bytes are not one — an
     * unknown pixel format, an implausible size, a truncated transfer, or an error message where a frame was
     * expected.
     */
    static BufferedImage decode(byte[] raw) {
        if (raw == null || raw.length < HEADER_LEGACY) {
            return null;
        }
        int width = intLe(raw, 0);
        int height = intLe(raw, 4);
        int format = intLe(raw, 8);
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            return null;
        }
        int bytesPerPixel = bytesPerPixel(format);
        if (bytesPerPixel == 0) {
            return null;
        }

        long pixelBytes = (long) width * height * bytesPerPixel;
        int offset;
        if (raw.length - HEADER_LEGACY == pixelBytes) {
            offset = HEADER_LEGACY;
        } else if (raw.length - HEADER_WITH_COLORSPACE == pixelBytes) {
            offset = HEADER_WITH_COLORSPACE;
        } else {
            return null;
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        readPixels(raw, offset, format, pixels);
        return image;
    }

    /** {@code 0} for a format we cannot lay out, which is how {@link #decode} rejects one. */
    private static int bytesPerPixel(int format) {
        return switch (format) {
            case RGBA_8888, RGBX_8888, BGRA_8888 -> 4;
            case RGB_888 -> 3;
            case RGB_565 -> 2;
            default -> 0;
        };
    }

    /**
     * Fills {@code pixels} with packed RGB. Rows are tight — {@code screencap} walks the buffer's stride
     * itself and writes {@code width × bytesPerPixel} per row — so this is one flat pass, not a row loop.
     *
     * <p>The alpha byte is dropped rather than carried: these frames come off a composited display where it is
     * meaningless (opaque, or a stale scratch value), and a bot matching templates against a surprise
     * alpha channel is a class of bug worth not having.
     */
    private static void readPixels(byte[] raw, int offset, int format, int[] pixels) {
        int at = offset;
        switch (format) {
            case RGBA_8888, RGBX_8888 -> {
                for (int i = 0; i < pixels.length; i++, at += 4) {
                    pixels[i] = (raw[at] & 0xFF) << 16 | (raw[at + 1] & 0xFF) << 8 | (raw[at + 2] & 0xFF);
                }
            }
            case BGRA_8888 -> {
                for (int i = 0; i < pixels.length; i++, at += 4) {
                    pixels[i] = (raw[at + 2] & 0xFF) << 16 | (raw[at + 1] & 0xFF) << 8 | (raw[at] & 0xFF);
                }
            }
            case RGB_888 -> {
                for (int i = 0; i < pixels.length; i++, at += 3) {
                    pixels[i] = (raw[at] & 0xFF) << 16 | (raw[at + 1] & 0xFF) << 8 | (raw[at + 2] & 0xFF);
                }
            }
            case RGB_565 -> {
                for (int i = 0; i < pixels.length; i++, at += 2) {
                    int value = (raw[at] & 0xFF) | (raw[at + 1] & 0xFF) << 8;
                    pixels[i] = expand(value >>> 11, 31) << 16
                            | expand(value >>> 5 & 0x3F, 63) << 8
                            | expand(value & 0x1F, 31);
                }
            }
            default -> throw new IllegalStateException("unreachable: format " + format + " has no byte width");
        }
    }

    /**
     * Scales a 5- or 6-bit channel to 8 bits so that full is full — {@code 31 → 255}, not {@code 31 → 248}.
     * A plain left-shift caps white at {@code 0xF8F8F8}, which is a real (if small) template-matching error.
     */
    private static int expand(int value, int max) {
        return (value * 255 + max / 2) / max;
    }

    /** Little-endian, which is every device that runs Android; the header is written in native byte order. */
    private static int intLe(byte[] raw, int at) {
        return (raw[at] & 0xFF)
                | (raw[at + 1] & 0xFF) << 8
                | (raw[at + 2] & 0xFF) << 16
                | (raw[at + 3] & 0xFF) << 24;
    }
}
