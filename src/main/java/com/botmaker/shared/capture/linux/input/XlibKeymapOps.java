package com.botmaker.shared.capture.linux.input;

import com.botmaker.shared.capture.linux.X11;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * {@link KeymapOps} over a live X display via {@code libX11} ({@code XDisplayKeycodes} /
 * {@code XGetKeyboardMapping} / {@code XChangeKeyboardMapping}). The keycode range and row width are read once
 * at construction — they don't change for the life of a server — while {@link #keysymsFor} reads through to
 * the server each call so a snapshot taken just before a rebind reflects the current mapping.
 *
 * <p>A KeySym is an unsigned long (8 bytes on a 64-bit server); the mapping table is row-major
 * ({@code keysymsPerKeycode} entries per keycode).
 */
final class XlibKeymapOps implements KeymapOps {

    private final Pointer display;
    private final int minKeycode;
    private final int maxKeycode;
    private final int perKeycode;

    XlibKeymapOps(Pointer display) {
        this.display = display;
        IntByReference min = new IntByReference();
        IntByReference max = new IntByReference();
        X11.INSTANCE.XDisplayKeycodes(display, min, max);
        this.minKeycode = min.getValue();
        this.maxKeycode = max.getValue();
        // The row width is reported by the same call that reads a mapping; read one keycode to learn it.
        IntByReference per = new IntByReference();
        Pointer syms = X11.INSTANCE.XGetKeyboardMapping(display, (byte) this.minKeycode, 1, per);
        this.perKeycode = Math.max(1, per.getValue());
        if (syms != null) {
            X11.INSTANCE.XFree(syms);
        }
    }

    @Override
    public int minKeycode() {
        return minKeycode;
    }

    @Override
    public int maxKeycode() {
        return maxKeycode;
    }

    @Override
    public int keysymsPerKeycode() {
        return perKeycode;
    }

    @Override
    public long[] keysymsFor(int keycode) {
        IntByReference per = new IntByReference();
        Pointer syms = X11.INSTANCE.XGetKeyboardMapping(display, (byte) keycode, 1, per);
        if (syms == null) {
            return new long[perKeycode];
        }
        try {
            return syms.getLongArray(0, perKeycode);
        } finally {
            X11.INSTANCE.XFree(syms);
        }
    }

    @Override
    public void rebind(int keycode, long[] keysyms) {
        try (Memory mem = new Memory((long) perKeycode * Native.LONG_SIZE)) {
            for (int i = 0; i < perKeycode; i++) {
                mem.setLong((long) i * Native.LONG_SIZE, i < keysyms.length ? keysyms[i] : 0L);
            }
            X11.INSTANCE.XChangeKeyboardMapping(display, keycode, perKeycode, mem, 1);
        }
    }

    @Override
    public void sync() {
        X11.INSTANCE.XSync(display, false);
    }
}
