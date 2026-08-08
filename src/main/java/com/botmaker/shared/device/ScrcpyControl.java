package com.botmaker.shared.device;

import java.nio.ByteBuffer;

/**
 * <b>scrcpy control messages, as bytes.</b> Pure and static: give it a gesture and a screen size and it
 * returns the exact buffer that goes down the control socket. No socket, no device, no threads — which is the
 * whole reason it is its own type, because it is the half of the fast path that can be asserted without a
 * phone.
 *
 * <h2>What this replaces, and the size of the win</h2>
 *
 * <p>{@code AdbDevice.tap} runs {@code input tap x y}. {@code input} is a shell script that execs {@code
 * app_process} — <b>a JVM start on the device, per tap</b>. Phase 2's held shell removed the fork around that
 * and could not touch the JVM inside it. These messages go straight to a process that is already running and
 * already holds the injection binder: the tap is a 32-byte write.
 *
 * <h2>Layout, and the version it is written to</h2>
 *
 * <p>Every message is big-endian, one type byte first. The layouts below are scrcpy <b>2.1 through 3.x</b>;
 * {@link ScrcpyServer.Version#supported()} refuses anything older, and it refuses it rather than approximating
 * because a message encoded one field short is not rejected by the server — it reads the following message's
 * bytes as this one's tail, and the stream desynchronises silently from there. That is the failure this
 * refusal exists to make impossible.
 *
 * <p><b>These layouts are transcribed, not measured.</b> Nothing in this repo has yet exchanged a byte with a
 * real scrcpy server, so treat a gesture that lands in the wrong place as a layout bug here first — the
 * per-field comments are written to make that diff readable.
 */
final class ScrcpyControl {

    private ScrcpyControl() {}

    /** Message types, as the server's {@code ControlMessageReader} numbers them. */
    private static final byte TYPE_INJECT_KEYCODE = 0;
    private static final byte TYPE_INJECT_TOUCH = 2;
    private static final byte TYPE_INJECT_SCROLL = 3;

    /** {@code MotionEvent} actions — the only three a synthetic gesture needs. */
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;
    static final int ACTION_MOVE = 2;

    /** {@code KeyEvent} actions. */
    static final int KEY_DOWN = 0;
    static final int KEY_UP = 1;

    /**
     * The pointer id scrcpy reserves for an injected finger that is not the mouse. Using the mouse's id
     * ({@code -1}) instead would make Android treat the gesture as a hovering pointer on some devices.
     */
    private static final long POINTER_VIRTUAL_FINGER = -2L;

    /** Pressure is a 16-bit fixed-point fraction of 1.0, so "fully pressed" is every bit set. */
    private static final short PRESSURE_FULL = (short) 0xFFFF;

    /**
     * One touch event.
     *
     * <p>{@code screenWidth}/{@code screenHeight} are not decoration: the server rescales the point by the
     * ratio of this size to the device's real one, so a wrong size here is a tap that lands somewhere else
     * while every log line still says the right numbers. Pass the size of the frame the coordinates were taken
     * from, which — because this stack sets no {@code max_size} — is the device framebuffer itself.
     */
    static byte[] touch(int action, int x, int y, int screenWidth, int screenHeight) {
        ByteBuffer buffer = ByteBuffer.allocate(32);
        buffer.put(TYPE_INJECT_TOUCH);
        buffer.put((byte) action);
        buffer.putLong(POINTER_VIRTUAL_FINGER);
        position(buffer, x, y, screenWidth, screenHeight);
        // A finger that is up presses with no force; anything else would read as a pressed pointer being lifted.
        buffer.putShort(action == ACTION_UP ? 0 : PRESSURE_FULL);
        buffer.putInt(0);   // action_button — 2.1+; zero for a finger, which has no button
        buffer.putInt(0);   // buttons — likewise
        return buffer.array();
    }

    /**
     * One scroll notch at a point. {@code horizontal}/{@code vertical} are fractions in {@code [-1, 1]}, sent
     * as 16-bit fixed point — the encoding scrcpy moved to at 2.0, replacing 1.x's plain ints.
     */
    static byte[] scroll(int x, int y, int screenWidth, int screenHeight,
                         double horizontal, double vertical) {
        ByteBuffer buffer = ByteBuffer.allocate(21);
        buffer.put(TYPE_INJECT_SCROLL);
        position(buffer, x, y, screenWidth, screenHeight);
        buffer.putShort(fixedPoint(horizontal));
        buffer.putShort(fixedPoint(vertical));
        buffer.putInt(0);   // buttons
        return buffer.array();
    }

    /** One key event by Android keycode — the direct form of {@code input keyevent}. */
    static byte[] keycode(int action, int keyCode, int metaState) {
        ByteBuffer buffer = ByteBuffer.allocate(14);
        buffer.put(TYPE_INJECT_KEYCODE);
        buffer.put((byte) action);
        buffer.putInt(keyCode);
        buffer.putInt(0);   // repeat
        buffer.putInt(metaState);
        return buffer.array();
    }

    /**
     * A point plus the frame it was measured in — 12 bytes, and the same shape in every message that carries
     * one. The dimensions are <b>unsigned 16-bit</b>, which is ample for a framebuffer and is why they are
     * written as shorts rather than ints.
     */
    private static void position(ByteBuffer buffer, int x, int y, int screenWidth, int screenHeight) {
        buffer.putInt(x);
        buffer.putInt(y);
        buffer.putShort((short) screenWidth);
        buffer.putShort((short) screenHeight);
    }

    /**
     * A fraction of 1.0 as a signed 16-bit fixed-point value, clamped. {@code 1.0} is {@link Short#MAX_VALUE}
     * rather than {@code 0x8000}, so the round trip through the server's own conversion is symmetric.
     */
    private static short fixedPoint(double value) {
        double clamped = Math.max(-1.0, Math.min(1.0, value));
        return (short) Math.round(clamped * Short.MAX_VALUE);
    }
}
