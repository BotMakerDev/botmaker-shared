package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.FrameGrab;
import com.botmaker.shared.capture.FrameSink;
import com.sun.jna.Memory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XShmGrabTest {

    private static Memory ints(int... pixels) {
        Memory memory = new Memory(pixels.length * 4L);
        memory.write(0, pixels, 0, pixels.length);
        return memory;
    }

    @Test
    void xrgbRowsAreCopiedWholeAndMadeOpaque() {
        int[] into = new int[4];
        XShmGrab.argb(ints(0x00123456, 0x00000000, 0x7F000000, 0x00ABCDEF), 2, 2, 8, 32, 0, 0, 0, into);
        assertArrayEquals(new int[]{0xFF123456, 0xFF000000, 0xFF000000, 0xFFABCDEF}, into);
    }

    @Test
    void aRowPaddedPastItsWidthIsReadRowByRow() {
        int[] into = new int[2];
        XShmGrab.argb(ints(0x00010203, 0x00DEAD00, 0x00040506, 0x00BEEF00), 1, 2, 8, 32,
                0xFF0000, 0x00FF00, 0x0000FF, into);
        assertArrayEquals(new int[]{0xFF010203, 0xFF040506}, into);
    }

    @Test
    void otherMasksAreReadThroughThem() {
        // Blue in the high byte, red in the low one: BGR.
        int[] into = new int[1];
        XShmGrab.argb(ints(0x00030201), 1, 1, 4, 32, 0x0000FF, 0x00FF00, 0xFF0000, into);
        assertArrayEquals(new int[]{0xFF010203}, into);
    }

    @Test
    void threeBytesAPixelAreAssembled() {
        Memory memory = new Memory(8);
        memory.write(0, new byte[]{0x03, 0x02, 0x01, 0x06, 0x05, 0x04, 0, 0}, 0, 8);
        int[] into = new int[2];
        XShmGrab.argb(memory, 2, 1, 8, 24, 0xFF0000, 0x00FF00, 0x0000FF, into);
        assertArrayEquals(new int[]{0xFF010203, 0xFF040506}, into);
    }

    @Test
    void aFrameOfAnImageIsWrittenIntoTheSinksArray() {
        BufferedImage frame = new BufferedImage(2, 1, BufferedImage.TYPE_3BYTE_BGR);
        frame.setRGB(1, 0, 0x00FF80);
        int[] into = new int[3];
        assertTrue(FrameGrab.of(() -> frame).grab((w, h) -> into));
        assertArrayEquals(new int[]{0xFF000000, 0xFF00FF80, 0}, into);
        assertFalse(FrameGrab.of(() -> null).grab((w, h) -> into));
    }

    @Test
    void aFrameIsBlackOnlyWhenNoSampleIsLit() {
        int[] pixels = new int[40 * 40];
        java.util.Arrays.fill(pixels, 0xFF000000);
        assertTrue(FrameSink.black(pixels, 40, 40));
        pixels[0] = 0xFF000001;
        assertFalse(FrameSink.black(pixels, 40, 40));
    }
}
