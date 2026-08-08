package com.botmaker.shared.device;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire layout of the control messages — the half of the fast path that can be asserted without a phone.
 *
 * <p><b>What these tests can and cannot tell you.</b> They check that the encoder produces the layout this
 * repo wrote down; they cannot check that the layout is the one a real scrcpy server reads, because nothing
 * here has ever spoken to one. That distinction is worth keeping in mind when a gesture lands in the wrong
 * place on a real device: these passing means the bug is in the transcription, not in the encoding of it.
 *
 * <p>Sizes are asserted explicitly because a message one byte short is <em>not</em> rejected by the server —
 * it reads the next message's first byte as this one's last, and every message after that is garbage. There is
 * no error to observe, which is exactly why the length is worth a test.
 */
class ScrcpyControlTest {

    private static final int TYPE_INJECT_KEYCODE = 0;
    private static final int TYPE_INJECT_TOUCH = 2;
    private static final int TYPE_INJECT_SCROLL = 3;

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2400;

    @Test
    void aTouchIsThirtyTwoBytesAndStartsWithItsType() {
        byte[] message = ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, 100, 200, WIDTH, HEIGHT);

        assertEquals(32, message.length, "a short touch message desynchronises the stream silently");
        assertEquals(TYPE_INJECT_TOUCH, message[0]);
        assertEquals(ScrcpyControl.ACTION_DOWN, message[1]);
    }

    /**
     * The point and the frame it was measured in. The server rescales by the ratio of this size to the
     * device's own, so a wrong size here is a tap that lands elsewhere while every number still looks right.
     */
    @Test
    void aTouchCarriesThePointAndTheFrameItWasMeasuredIn() {
        ByteBuffer message = ByteBuffer.wrap(
                ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, 540, 1200, WIDTH, HEIGHT));

        message.position(10);   // past the type, the action and the 8-byte pointer id
        assertEquals(540, message.getInt());
        assertEquals(1200, message.getInt());
        assertEquals(WIDTH, message.getShort() & 0xFFFF);
        assertEquals(HEIGHT, message.getShort() & 0xFFFF);
    }

    /** A finger being lifted presses with no force; anything else reads as a pressed pointer going up. */
    @Test
    void pressureIsFullWhileDownAndZeroOnRelease() {
        assertEquals((short) 0xFFFF, pressureOf(ScrcpyControl.ACTION_DOWN));
        assertEquals((short) 0xFFFF, pressureOf(ScrcpyControl.ACTION_MOVE));
        assertEquals((short) 0, pressureOf(ScrcpyControl.ACTION_UP));
    }

    private static short pressureOf(int action) {
        return ByteBuffer.wrap(ScrcpyControl.touch(action, 1, 1, WIDTH, HEIGHT)).getShort(22);
    }

    /**
     * A framebuffer is comfortably inside 16 bits, but the field <em>is</em> 16 bits, and writing it as an int
     * would shift everything after it. 2400 has its high byte set, so this catches a sign-extension slip too.
     */
    @Test
    void screenDimensionsSurviveTheSixteenBitField() {
        ByteBuffer message = ByteBuffer.wrap(ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, 0, 0, 1440, 3120));
        assertEquals(1440, message.getShort(18) & 0xFFFF);
        assertEquals(3120, message.getShort(20) & 0xFFFF);
    }

    @Test
    void aScrollIsTwentyOneBytesAndStartsWithItsType() {
        byte[] message = ScrcpyControl.scroll(10, 20, WIDTH, HEIGHT, 0, 1);

        assertEquals(21, message.length);
        assertEquals(TYPE_INJECT_SCROLL, message[0]);
    }

    /** Scroll deltas are 16-bit fixed point since 2.0 — a fraction of 1.0, not a notch count. */
    @Test
    void scrollDeltasAreFixedPointFractions() {
        ByteBuffer up = ByteBuffer.wrap(ScrcpyControl.scroll(0, 0, WIDTH, HEIGHT, 0, 1));
        assertEquals(0, up.getShort(13), "no horizontal component was asked for");
        assertEquals(Short.MAX_VALUE, up.getShort(15), "a full notch up is +1.0");

        ByteBuffer down = ByteBuffer.wrap(ScrcpyControl.scroll(0, 0, WIDTH, HEIGHT, 0, -1));
        assertEquals(-Short.MAX_VALUE, down.getShort(15), "a full notch down is -1.0");
    }

    /** Out-of-range deltas clamp rather than wrapping — a wrapped value scrolls hard the other way. */
    @Test
    void scrollDeltasClampInsteadOfWrapping() {
        assertEquals(Short.MAX_VALUE,
                ByteBuffer.wrap(ScrcpyControl.scroll(0, 0, WIDTH, HEIGHT, 0, 40)).getShort(15));
        assertEquals(-Short.MAX_VALUE,
                ByteBuffer.wrap(ScrcpyControl.scroll(0, 0, WIDTH, HEIGHT, 0, -40)).getShort(15));
    }

    @Test
    void aKeycodeIsFourteenBytesAndCarriesItsCode() {
        byte[] message = ScrcpyControl.keycode(ScrcpyControl.KEY_DOWN, 4, 0);

        assertEquals(14, message.length);
        assertEquals(TYPE_INJECT_KEYCODE, message[0]);
        assertEquals(ScrcpyControl.KEY_DOWN, message[1]);
        assertEquals(4, ByteBuffer.wrap(message).getInt(2), "KEYCODE_BACK");
    }

    /** Down and up must differ in the action byte alone, or a press and a release are the same event. */
    @Test
    void downAndUpDifferOnlyInTheirAction() {
        byte[] down = ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, 7, 9, WIDTH, HEIGHT);
        byte[] up = ScrcpyControl.touch(ScrcpyControl.ACTION_UP, 7, 9, WIDTH, HEIGHT);

        assertNotEquals(down[1], up[1]);
        for (int i = 2; i < 22; i++) {
            assertEquals(down[i], up[i], "byte " + i + " is not part of the action");
        }
    }

    /** The pointer id is scrcpy's virtual finger, not the mouse — the mouse's id hovers on some devices. */
    @Test
    void touchesUseTheVirtualFingerPointer() {
        long pointer = ByteBuffer.wrap(
                ScrcpyControl.touch(ScrcpyControl.ACTION_DOWN, 0, 0, WIDTH, HEIGHT)).getLong(2);
        assertEquals(-2L, pointer);
        assertTrue(pointer != -1L, "-1 is the mouse pointer, which is a different gesture");
    }
}
