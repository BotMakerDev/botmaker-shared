package com.botmaker.shared.capture.mac;

import java.util.HashMap;
import java.util.Map;

/**
 * X11 keysyms to macOS virtual key codes ({@code kVK_*}).
 *
 * <p>{@code NativeController.keyDown} takes "a per-OS native key code", and the SDK's {@code Key.nativeCode()}
 * answers with the Windows virtual key on Windows and the X11 keysym everywhere else — macOS included. Rather
 * than give {@code Key} a third column, the Mac controller reads that keysym and translates it here: a bot's
 * {@code Key} reaches macOS untouched, and the translation lives next to the only code that needs it.
 *
 * <p>Key codes name a physical key on an ANSI keyboard, as {@code kVK_ANSI_*} do; letters map whatever their
 * case, the way {@code Key} uses the lowercase keysym for the key. {@code Alt} is Option and {@code Meta}/Super
 * is Command. A keysym with no Mac key answers {@link #NONE}.
 */
final class MacKeyCodes {

    static final int NONE = -1;

    static final long FLAG_SHIFT = 0x20000;
    static final long FLAG_CONTROL = 0x40000;
    static final long FLAG_OPTION = 0x80000;
    static final long FLAG_COMMAND = 0x100000;

    private static final Map<Integer, Integer> BY_KEYSYM = new HashMap<>();

    static {
        // Letters, ANSI positions (kVK_ANSI_A = 0x00 …), lowercase keysyms; uppercase added below.
        int[] letters = {0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E,
                0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06};
        for (int i = 0; i < 26; i++) {
            BY_KEYSYM.put('a' + i, letters[i]);
            BY_KEYSYM.put('A' + i, letters[i]);
        }
        int[] digits = {0x1D, 0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19};
        for (int i = 0; i < 10; i++) {
            BY_KEYSYM.put('0' + i, digits[i]);
        }
        put(' ', 0x31);
        put('`', 0x32);
        put('-', 0x1B);
        put('=', 0x18);
        put('[', 0x21);
        put(']', 0x1E);
        put('\\', 0x2A);
        put(';', 0x29);
        put('\'', 0x27);
        put(',', 0x2B);
        put('.', 0x2F);
        put('/', 0x2C);

        int[] functionKeys = {0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D, 0x67, 0x6F,
                0x69, 0x6B, 0x71, 0x6A, 0x40, 0x4F, 0x50, 0x5A};
        for (int i = 0; i < functionKeys.length; i++) {
            BY_KEYSYM.put(0xFFBE + i, functionKeys[i]);
        }

        put(0xFF0D, 0x24); // Return
        put(0xFF1B, 0x35); // Escape
        put(0xFF09, 0x30); // Tab
        put(0xFF08, 0x33); // BackSpace — kVK_Delete
        put(0xFFFF, 0x75); // Delete — kVK_ForwardDelete
        put(0xFF63, 0x72); // Insert — kVK_Help, where Insert sits on a Mac extended keyboard
        put(0xFF50, 0x73); // Home
        put(0xFF57, 0x77); // End
        put(0xFF55, 0x74); // Page Up
        put(0xFF56, 0x79); // Page Down
        put(0xFF51, 0x7B); // Left
        put(0xFF52, 0x7E); // Up
        put(0xFF53, 0x7C); // Right
        put(0xFF54, 0x7D); // Down
        put(0xFFE5, 0x39); // Caps Lock
        put(0xFF7F, 0x47); // Num Lock — kVK_ANSI_KeypadClear, the key in its place

        put(0xFFE1, 0x38); // Shift_L
        put(0xFFE2, 0x3C); // Shift_R
        put(0xFFE3, 0x3B); // Control_L
        put(0xFFE4, 0x3E); // Control_R
        put(0xFFE9, 0x3A); // Alt_L — Option
        put(0xFFEA, 0x3D); // Alt_R
        put(0xFFE7, 0x37); // Meta_L — Command
        put(0xFFE8, 0x36); // Meta_R
        put(0xFFEB, 0x37); // Super_L — Command
        put(0xFFEC, 0x36); // Super_R

        int[] keypad = {0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5B, 0x5C};
        for (int i = 0; i < 10; i++) {
            BY_KEYSYM.put(0xFFB0 + i, keypad[i]);
        }
        put(0xFFAB, 0x45); // KP_Add
        put(0xFFAD, 0x4E); // KP_Subtract
        put(0xFFAA, 0x43); // KP_Multiply
        put(0xFFAF, 0x4B); // KP_Divide
        put(0xFFAE, 0x41); // KP_Decimal
        put(0xFF8D, 0x4C); // KP_Enter
    }

    private MacKeyCodes() {
    }

    private static void put(int keysym, int keyCode) {
        BY_KEYSYM.put(keysym, keyCode);
    }

    /** The Mac key for {@code keysym}, or {@link #NONE}. */
    static int fromKeysym(int keysym) {
        return BY_KEYSYM.getOrDefault(keysym, NONE);
    }

    /**
     * The modifier flag a Mac key holds down, or 0. Synthetic events carry no modifier state of their own, so
     * the controller adds the flags of the modifiers it is holding to every event it posts.
     */
    static long flagOf(int keyCode) {
        return switch (keyCode) {
            case 0x38, 0x3C -> FLAG_SHIFT;
            case 0x3B, 0x3E -> FLAG_CONTROL;
            case 0x3A, 0x3D -> FLAG_OPTION;
            case 0x37, 0x36 -> FLAG_COMMAND;
            default -> 0;
        };
    }
}
