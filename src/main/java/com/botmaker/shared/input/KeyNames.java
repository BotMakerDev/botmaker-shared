package com.botmaker.shared.input;

import java.util.Map;

/**
 * Names for the keysyms an {@link InputEvent} carries, where no {@code libX11} is around to ask
 * ({@code XKeysymToString}): on macOS, or a Linux without X. Function, editing and keypad keys get X's names,
 * underscores read as spaces ({@code Pause}, {@code Scroll Lock}, {@code F12}), so the keys a stop key is
 * likely to be read the same on both. A character is named by itself ({@code ,}, {@code §}) where X spells it
 * out ({@code comma}, {@code section}).
 */
public final class KeyNames {

    private static final Map<Long, String> NAMED = Map.ofEntries(
            Map.entry(0xFF08L, "BackSpace"), Map.entry(0xFF09L, "Tab"), Map.entry(0xFF0DL, "Return"),
            Map.entry(0xFF13L, "Pause"), Map.entry(0xFF14L, "Scroll Lock"), Map.entry(0xFF1BL, "Escape"),
            Map.entry(0xFF50L, "Home"), Map.entry(0xFF51L, "Left"), Map.entry(0xFF52L, "Up"),
            Map.entry(0xFF53L, "Right"), Map.entry(0xFF54L, "Down"), Map.entry(0xFF55L, "Prior"),
            Map.entry(0xFF56L, "Next"), Map.entry(0xFF57L, "End"), Map.entry(0xFF61L, "Print"),
            Map.entry(0xFF63L, "Insert"), Map.entry(0xFF7FL, "Num Lock"), Map.entry(0xFF8DL, "KP Enter"),
            Map.entry(0xFFAAL, "KP Multiply"), Map.entry(0xFFABL, "KP Add"), Map.entry(0xFFADL, "KP Subtract"),
            Map.entry(0xFFAEL, "KP Decimal"), Map.entry(0xFFAFL, "KP Divide"), Map.entry(0xFFE1L, "Shift L"),
            Map.entry(0xFFE2L, "Shift R"), Map.entry(0xFFE3L, "Control L"), Map.entry(0xFFE4L, "Control R"),
            Map.entry(0xFFE5L, "Caps Lock"), Map.entry(0xFFE9L, "Alt L"), Map.entry(0xFFEAL, "Alt R"),
            Map.entry(0xFFEBL, "Super L"), Map.entry(0xFFECL, "Super R"), Map.entry(0xFFFFL, "Delete"),
            Map.entry(0x20L, "space"));

    private KeyNames() {
    }

    /** X's name for {@code keysym}, or {@code null} when it is none this table knows. */
    public static String name(long keysym) {
        String named = NAMED.get(keysym);
        if (named != null) return named;
        if (keysym >= 0xFFBE && keysym <= 0xFFE0) return "F" + (keysym - 0xFFBE + 1);
        if (keysym >= 0xFFB0 && keysym <= 0xFFB9) return "KP " + (keysym - 0xFFB0);
        // A character: Latin-1 is its own code point, the rest of Unicode 0x01000000 | code point.
        long cp = keysym >= 0x01000100L && keysym <= 0x0110FFFFL ? keysym - 0x01000000L : keysym;
        boolean character = (cp > 0x20 && cp < 0x7F) || (cp >= 0xA0 && cp <= 0xFF) || keysym >= 0x01000100L;
        if (character && cp <= Character.MAX_CODE_POINT) return Character.toString((int) cp);
        return null;
    }
}
