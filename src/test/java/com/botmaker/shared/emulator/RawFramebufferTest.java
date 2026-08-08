package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RawFramebuffer} against synthesised {@code screencap} payloads.
 *
 * <p>The interesting cases are all about the header being ambiguous: nothing in the stream says whether it
 * carries the API 29 colorspace word, so the decoder infers it from the payload length. These pin both
 * layouts decoding, and — the part that makes the fallback in {@link AdbDevice#screencap()} safe — that
 * anything failing the arithmetic is rejected rather than decoded into garbage.
 */
class RawFramebufferTest {

    private static final int RGBA_8888 = 1;
    private static final int BGRA_8888 = 5;
    private static final int RGB_565 = 4;

    /** Header words are little-endian, followed by tightly-packed rows — the format screencap writes. */
    private static byte[] frame(int width, int height, int format, int headerWords, byte[] pixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] header = headerWords == 4
                ? new int[]{width, height, format, 0}
                : new int[]{width, height, format};
        for (int word : header) {
            out.write(word & 0xFF);
            out.write(word >>> 8 & 0xFF);
            out.write(word >>> 16 & 0xFF);
            out.write(word >>> 24 & 0xFF);
        }
        out.writeBytes(pixels);
        return out.toByteArray();
    }

    @Test
    void decodesTheLegacyThreeWordHeader() {
        byte[] pixels = {(byte) 0xFF, 0x00, 0x00, (byte) 0xFF, 0x00, (byte) 0xFF, 0x00, (byte) 0xFF};
        BufferedImage image = RawFramebuffer.decode(frame(2, 1, RGBA_8888, 3, pixels));

        assertNotNull(image);
        assertEquals(2, image.getWidth());
        assertEquals(1, image.getHeight());
        assertEquals(0xFF0000, image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(0x00FF00, image.getRGB(1, 0) & 0xFFFFFF);
    }

    /** From API 29 there is a fourth word. Same pixels, four more header bytes, same picture. */
    @Test
    void decodesTheApi29ColorspaceHeader() {
        byte[] pixels = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};
        BufferedImage image = RawFramebuffer.decode(frame(1, 1, RGBA_8888, 4, pixels));

        assertNotNull(image);
        assertEquals(0x0000FF, image.getRGB(0, 0) & 0xFFFFFF);
    }

    /** The one format whose channel order differs — getting this backwards swaps red and blue silently. */
    @Test
    void bgraReadsChannelsInDeviceOrder() {
        byte[] pixels = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF};
        BufferedImage image = RawFramebuffer.decode(frame(1, 1, BGRA_8888, 4, pixels));

        assertNotNull(image);
        assertEquals(0xFF0000, image.getRGB(0, 0) & 0xFFFFFF, "the third byte is red in BGRA");
    }

    /** A 5/6-bit channel at full scale must reach 255; a plain shift caps white at 0xF8FCF8. */
    @Test
    void rgb565WhiteIsActuallyWhite() {
        byte[] pixels = {(byte) 0xFF, (byte) 0xFF};
        BufferedImage image = RawFramebuffer.decode(frame(1, 1, RGB_565, 4, pixels));

        assertNotNull(image);
        assertEquals(0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF);
    }

    /**
     * The payload length is the whole validation, so a short read must not decode. This is the case that
     * would otherwise hand a bot a half-black frame it would happily try to match against.
     */
    @Test
    void aTruncatedTransferIsRejected() {
        byte[] full = frame(4, 4, RGBA_8888, 4, new byte[4 * 4 * 4]);
        byte[] short_ = java.util.Arrays.copyOf(full, full.length - 8);

        assertNull(RawFramebuffer.decode(short_));
    }

    @Test
    void anUnknownPixelFormatIsRejected() {
        assertNull(RawFramebuffer.decode(frame(1, 1, 99, 4, new byte[4])));
    }

    /** Not every failed screencap is silent — some print a message, which must not parse as a frame. */
    @Test
    void arbitraryBytesAreRejectedRatherThanDecoded() {
        assertNull(RawFramebuffer.decode("screencap: permission denied\n".getBytes()));
        assertNull(RawFramebuffer.decode(new byte[0]));
        assertNull(RawFramebuffer.decode(null));
    }

    /** Garbage dimensions must be rejected before they size an allocation. */
    @Test
    void implausibleDimensionsAreRejected() {
        assertNull(RawFramebuffer.decode(frame(0, 100, RGBA_8888, 4, new byte[0])));
        assertNull(RawFramebuffer.decode(frame(-1, 100, RGBA_8888, 4, new byte[0])));
        assertNull(RawFramebuffer.decode(frame(999_999, 999_999, RGBA_8888, 4, new byte[0])));
    }

    /** A phone-shaped frame, to be sure nothing overflows at a realistic size. */
    @Test
    void decodesAPhoneSizedFrame() {
        BufferedImage image =
                RawFramebuffer.decode(frame(1080, 2400, RGBA_8888, 4, new byte[1080 * 2400 * 4]));

        assertNotNull(image);
        assertEquals(1080, image.getWidth());
        assertEquals(2400, image.getHeight());
    }
}
