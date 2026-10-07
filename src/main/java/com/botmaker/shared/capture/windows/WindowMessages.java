package com.botmaker.shared.capture.windows;

/**
 * How a gesture is spelled as window messages: which message, and what its {@code wParam} and {@code lParam}
 * carry. Pure arithmetic over the Win32 ABI, kept apart from the posting ({@link PostedInput}) so it can be
 * checked on any OS — a wrong bit here does not throw, the game just reads a different button or a key it
 * never saw pressed.
 *
 * <p>Buttons are numbered the X11 way throughout the codebase (1/2/3 = left/middle/right, 8/9 = back/forward),
 * because that is what the SDK's {@code MouseButton.code()} reports; anything else reads as left.
 */
final class WindowMessages {

    private WindowMessages() {}

    /** The message for pressing or releasing {@code button}. */
    static int buttonMessage(int button, boolean press) {
        return switch (button) {
            case 2 -> press ? User32.WM_MBUTTONDOWN : User32.WM_MBUTTONUP;
            case 3 -> press ? User32.WM_RBUTTONDOWN : User32.WM_RBUTTONUP;
            case 8, 9 -> press ? User32.WM_XBUTTONDOWN : User32.WM_XBUTTONUP;
            default -> press ? User32.WM_LBUTTONDOWN : User32.WM_LBUTTONUP;
        };
    }

    /** {@code button}'s {@code MK_*} bit, the flag a mouse message's {@code wParam} reports it held with. */
    static int buttonFlag(int button) {
        return switch (button) {
            case 2 -> User32.MK_MBUTTON;
            case 3 -> User32.MK_RBUTTON;
            case 8 -> User32.MK_XBUTTON1;
            case 9 -> User32.MK_XBUTTON2;
            default -> User32.MK_LBUTTON;
        };
    }

    /**
     * The {@code wParam} of a button message, given the buttons held once it has happened — a press reports its
     * own button held, a release does not. A side button also names itself in the high word, since one message
     * serves both.
     */
    static long buttonWParam(int button, int heldAfter) {
        long held = heldAfter & 0xFFFF;
        return switch (button) {
            case 8 -> ((long) User32.XBUTTON1 << 16) | held;
            case 9 -> ((long) User32.XBUTTON2 << 16) | held;
            default -> held;
        };
    }

    /**
     * A point as a mouse message carries it: x in the low word, y in the high, each a signed 16-bit value
     * ({@code GET_X_LPARAM}). A point left of or above a window's client area is negative, and must survive.
     */
    static long pointLParam(int x, int y) {
        return ((long) (y & 0xFFFF) << 16) | (x & 0xFFFF);
    }

    /**
     * {@code WM_MOUSEWHEEL}'s {@code wParam} for one notch: the signed delta in the high word (positive = away
     * from the user, up), the held buttons in the low. One notch per message, so a long scroll never overflows
     * the 16-bit delta.
     */
    static long wheelWParam(boolean up, int held) {
        int delta = up ? User32.WHEEL_DELTA : -User32.WHEEL_DELTA;
        return ((long) (delta & 0xFFFF) << 16) | (held & 0xFFFF);
    }

    /**
     * A keystroke's {@code lParam}: a repeat count of 1, the scan code in bits 16–23, the extended-key flag in
     * bit 24, the context bit 29 when Alt is down, and on a release the previous-state and transition bits
     * (30, 31) that a window checks before believing a {@code WM_KEYUP}. A game that reads the scan code from
     * here (most that use messages at all) saw zero before.
     */
    static long keyLParam(ScanCode scan, boolean release, boolean alt) {
        long lParam = 1L | ((long) (scan.code() & 0xFF) << 16) | (scan.extended() ? 1L << 24 : 0)
                | (alt ? 1L << 29 : 0);
        return release ? lParam | 0xC0000000L : lParam;
    }

    private static final int VK_MENU = 0x12;
    private static final int VK_LMENU = 0xA4;
    private static final int VK_RMENU = 0xA5;
    private static final int VK_F10 = 0x79;

    /** Whether {@code vk} is an Alt key. */
    static boolean isAlt(int vk) {
        return vk == VK_MENU || vk == VK_LMENU || vk == VK_RMENU;
    }

    /**
     * The message a key travels in: {@code WM_SYSKEYDOWN/UP} for Alt, for F10, and for any key while Alt is down
     * ({@code alt}), which is how Windows delivers them; {@code WM_KEYDOWN/UP} otherwise.
     */
    static int keyMessage(int vk, boolean press, boolean alt) {
        boolean system = alt || isAlt(vk) || vk == VK_F10;
        if (system) {
            return press ? User32.WM_SYSKEYDOWN : User32.WM_SYSKEYUP;
        }
        return press ? User32.WM_KEYDOWN : User32.WM_KEYUP;
    }

    /**
     * A key's hardware scan code as {@code MapVirtualKey(vk, MAPVK_VK_TO_VSC_EX)} reports it, split into the
     * code and whether it has the {@code 0xE0}/{@code 0xE1} extended prefix — the bit that tells the arrow keys
     * from the numeric keypad's, which share the code.
     */
    record ScanCode(int code, boolean extended) {

        static final ScanCode NONE = new ScanCode(0, false);

        static ScanCode of(int vscEx) {
            int prefix = vscEx & 0xFF00;
            return new ScanCode(vscEx & 0xFF, prefix == 0xE000 || prefix == 0xE100);
        }

        boolean isNone() {
            return code == 0;
        }
    }
}
