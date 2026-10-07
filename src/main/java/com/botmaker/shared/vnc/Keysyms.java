package com.botmaker.shared.vnc;

import java.util.Map;

/**
 * X keysyms, which is what VNC sends for a key (RFC 6143 §7.5.4), from the key codes a {@link
 * com.botmaker.shared.capture.NativeController} is handed: a Windows virtual-key code on Windows, already a
 * keysym on Linux. Pure.
 */
public final class Keysyms {

    private Keysyms() {}

    /** Which kind of code a controller's {@code keyDown}/{@code keyUp} receive on this host. */
    public enum NativeKeys {
        /** Linux: the code is already an X keysym. */
        KEYSYM,
        /** Windows: the code is a virtual-key code. */
        VIRTUAL_KEY
    }

    /** Virtual keys whose keysym isn't their own value (letters, digits and space are). */
    private static final Map<Integer, Integer> VIRTUAL = Map.ofEntries(
            Map.entry(0x08, 0xFF08), // backspace
            Map.entry(0x09, 0xFF09), // tab
            Map.entry(0x0D, 0xFF0D), // enter
            Map.entry(0x10, 0xFFE1), // shift
            Map.entry(0x11, 0xFFE3), // ctrl
            Map.entry(0x12, 0xFFE9), // alt
            Map.entry(0x13, 0xFF13), // pause
            Map.entry(0x14, 0xFFE5), // caps lock
            Map.entry(0x1B, 0xFF1B), // escape
            Map.entry(0x21, 0xFF55), // page up
            Map.entry(0x22, 0xFF56), // page down
            Map.entry(0x23, 0xFF57), // end
            Map.entry(0x24, 0xFF50), // home
            Map.entry(0x25, 0xFF51), // left
            Map.entry(0x26, 0xFF52), // up
            Map.entry(0x27, 0xFF53), // right
            Map.entry(0x28, 0xFF54), // down
            Map.entry(0x2C, 0xFF61), // print screen
            Map.entry(0x2D, 0xFF63), // insert
            Map.entry(0x2E, 0xFFFF), // delete
            Map.entry(0x5B, 0xFFEB), // left Windows key
            Map.entry(0x5C, 0xFFEC), // right Windows key
            Map.entry(0x5D, 0xFF67), // menu
            Map.entry(0x6A, 0xFFAA), // numpad *
            Map.entry(0x6B, 0xFFAB), // numpad +
            Map.entry(0x6D, 0xFFAD), // numpad -
            Map.entry(0x6E, 0xFFAE), // numpad .
            Map.entry(0x6F, 0xFFAF), // numpad /
            Map.entry(0x90, 0xFF7F), // num lock
            Map.entry(0x91, 0xFF14), // scroll lock
            Map.entry(0xA0, 0xFFE1), // left shift
            Map.entry(0xA1, 0xFFE2), // right shift
            Map.entry(0xA2, 0xFFE3), // left ctrl
            Map.entry(0xA3, 0xFFE4), // right ctrl
            Map.entry(0xA4, 0xFFE9), // left alt
            Map.entry(0xA5, 0xFFEA), // right alt
            Map.entry(0xBA, 0x3B),   // ;
            Map.entry(0xBB, 0x3D),   // =
            Map.entry(0xBC, 0x2C),   // ,
            Map.entry(0xBD, 0x2D),   // -
            Map.entry(0xBE, 0x2E),   // .
            Map.entry(0xBF, 0x2F),   // /
            Map.entry(0xC0, 0x60),   // `
            Map.entry(0xDB, 0x5B),   // [
            Map.entry(0xDC, 0x5C),   // backslash
            Map.entry(0xDD, 0x5D),   // ]
            Map.entry(0xDE, 0x27));  // '

    /** The keysym for {@code code}, or {@code 0} when a virtual key has none. */
    public static int of(int code, NativeKeys kind) {
        if (kind == NativeKeys.KEYSYM) return code;
        if (code >= 'A' && code <= 'Z') return code + ('a' - 'A'); // the physical key, unshifted
        if (code >= '0' && code <= '9' || code == ' ') return code;
        if (code >= 0x60 && code <= 0x69) return 0xFFB0 + (code - 0x60); // numpad 0–9
        if (code >= 0x70 && code <= 0x87) return 0xFFBE + (code - 0x70); // F1–F24
        return VIRTUAL.getOrDefault(code, 0);
    }

    /**
     * The keysym that types {@code codePoint}: Latin-1 is its own keysym, the rest of Unicode is
     * {@code 0x01000000 + codePoint}, the control characters that are keys map to those keys, and any other
     * control character to {@code 0}, nothing to type.
     */
    public static int ofChar(int codePoint) {
        return switch (codePoint) {
            case '\n', '\r' -> 0xFF0D;
            case '\t' -> 0xFF09;
            case '\b' -> 0xFF08;
            case 0x1B -> 0xFF1B;
            case 0x7F -> 0xFFFF;
            default -> codePoint < 0x20 || codePoint >= 0x80 && codePoint < 0xA0 ? 0
                    : codePoint <= 0xFF ? codePoint : 0x01000000 + codePoint;
        };
    }

    /** The US-layout characters typed with Shift held. */
    private static final String SHIFTED = "~!@#$%^&*()_+{}|:\"<>?";

    /** Whether {@code codePoint} is typed with Shift on a US keyboard: capitals and the shifted symbols. */
    public static boolean needsShift(int codePoint) {
        return codePoint >= 'A' && codePoint <= 'Z' || codePoint < 0x80 && SHIFTED.indexOf(codePoint) >= 0;
    }
}
