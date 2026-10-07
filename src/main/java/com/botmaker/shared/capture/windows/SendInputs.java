package com.botmaker.shared.capture.windows;

import com.botmaker.shared.Diag;
import com.botmaker.shared.capture.windows.WindowMessages.ScanCode;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.ArrayList;
import java.util.List;

/**
 * The take-over path: input that enters Windows where a real mouse and keyboard's does, through
 * {@code SendInput}. Games that read raw input or DirectInput see it; they never see a posted message.
 *
 * <p>It replaced {@code mouse_event}/{@code keybd_event}, which Microsoft has marked superseded since XP and which
 * cannot express a Unicode character or carry the extended-key flag from a {@code VK_TO_VSC_EX} mapping.
 *
 * <p>The pointer is <em>placed</em> with {@code SetCursorPos} rather than an absolute {@code SendInput} move: an
 * absolute move is given in 0–65535 units over the virtual desktop, and the rounding back to a pixel is not
 * documented closely enough to land on the exact one a template match found. Buttons, the wheel and relative
 * motion — what raw-input games actually read — go through {@code SendInput}.
 */
final class SendInputs {

    private SendInputs() {}

    /** Press or release {@code button} (X11 numbering) where the pointer is. */
    static void button(int button, boolean press) {
        int xButton = switch (button) {
            case 8 -> User32.XBUTTON1;
            case 9 -> User32.XBUTTON2;
            default -> 0;
        };
        if (xButton != 0) {
            mouse(0, 0, xButton, press ? User32.MOUSEEVENTF_XDOWN : User32.MOUSEEVENTF_XUP);
            return;
        }
        int flag = switch (button) {
            case 2 -> press ? User32.MOUSEEVENTF_MIDDLEDOWN : User32.MOUSEEVENTF_MIDDLEUP;
            case 3 -> press ? User32.MOUSEEVENTF_RIGHTDOWN : User32.MOUSEEVENTF_RIGHTUP;
            default -> press ? User32.MOUSEEVENTF_LEFTDOWN : User32.MOUSEEVENTF_LEFTUP;
        };
        mouse(0, 0, 0, flag);
    }

    /** Wheel notches, one event each; positive is up, away from the user. */
    static void wheel(int notches) {
        int delta = notches > 0 ? User32.WHEEL_DELTA : -User32.WHEEL_DELTA;
        for (int i = 0; i < Math.abs(notches); i++) {
            mouse(0, 0, delta, User32.MOUSEEVENTF_WHEEL);
        }
    }

    /**
     * Relative motion, the event mouselook reads. Windows applies the user's pointer speed and "enhance pointer
     * precision" to it, so the cursor may travel more or less than {@code (dx, dy)}; a raw-input game reads the
     * unscaled delta.
     */
    static void moveBy(int dx, int dy) {
        mouse(dx, dy, 0, User32.MOUSEEVENTF_MOVE);
    }

    /** Press or release the key {@code vk} by its scan code, which is what DirectInput and raw input read. */
    static void key(int vk, boolean press) {
        ScanCode scan = ScanCode.of(vk, User32.INSTANCE.MapVirtualKeyW(vk, User32.MAPVK_VK_TO_VSC_EX));
        send(keyboard(vk, scan, press));
    }

    /**
     * Type {@code text}: each character through its key on the current layout, with the modifiers it needs, and
     * one the layout has no key for as a Unicode character (which a game reading scan codes will not see, but
     * a text field will).
     */
    static void type(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            short scan = User32.INSTANCE.VkKeyScanW(c);
            if (scan == -1) {
                send(unicode(c, true));
                send(unicode(c, false));
                continue;
            }
            int vk = scan & 0xFF;
            List<Integer> modifiers = modifiers(scan);
            modifiers.forEach(m -> key(m, true));
            key(vk, true);
            key(vk, false);
            modifiers.reversed().forEach(m -> key(m, false));
        }
    }

    private static final int VK_SHIFT = 0x10;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_MENU = 0x12;

    /**
     * The modifier keys {@code VkKeyScanW}'s high byte asks for: Shift (1), Ctrl (2), Alt (4). Ctrl and Alt
     * together are AltGr, which an AZERTY {@code @} or a German brace needs; with only Shift read, those
     * typed the bare key ({@code à} for {@code @}).
     */
    static List<Integer> modifiers(short vkScan) {
        int state = (vkScan >> 8) & 0xFF;
        List<Integer> keys = new ArrayList<>(3);
        if ((state & 1) != 0) keys.add(VK_SHIFT);
        if ((state & 2) != 0) keys.add(VK_CONTROL);
        if ((state & 4) != 0) keys.add(VK_MENU);
        return keys;
    }

    /**
     * The flags of a key event: by scan code when the key has one (with the extended bit when the code needs
     * it), by virtual key otherwise.
     */
    static int keyFlags(ScanCode scan, boolean press) {
        int flags = press ? 0 : User32.KEYEVENTF_KEYUP;
        if (scan.isNone()) {
            return flags;
        }
        flags |= User32.KEYEVENTF_SCANCODE;
        return scan.extended() ? flags | User32.KEYEVENTF_EXTENDEDKEY : flags;
    }

    private static WinUser.INPUT keyboard(int vk, ScanCode scan, boolean press) {
        WinUser.INPUT in = new WinUser.INPUT();
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        in.input.setType("ki");
        in.input.ki.wVk = new WinDef.WORD(scan.isNone() ? vk : 0);
        in.input.ki.wScan = new WinDef.WORD(scan.code());
        in.input.ki.dwFlags = new WinDef.DWORD(keyFlags(scan, press));
        in.input.ki.time = new WinDef.DWORD(0);
        in.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        return in;
    }

    private static WinUser.INPUT unicode(char c, boolean press) {
        WinUser.INPUT in = new WinUser.INPUT();
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        in.input.setType("ki");
        in.input.ki.wVk = new WinDef.WORD(0);
        in.input.ki.wScan = new WinDef.WORD(c);
        in.input.ki.dwFlags = new WinDef.DWORD(User32.KEYEVENTF_UNICODE | (press ? 0 : User32.KEYEVENTF_KEYUP));
        in.input.ki.time = new WinDef.DWORD(0);
        in.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        return in;
    }

    private static void mouse(int dx, int dy, int data, int flags) {
        WinUser.INPUT in = new WinUser.INPUT();
        in.type = new WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE);
        in.input.setType("mi");
        in.input.mi.dx = new WinDef.LONG(dx);
        in.input.mi.dy = new WinDef.LONG(dy);
        in.input.mi.mouseData = new WinDef.DWORD(data & 0xFFFFFFFFL);
        in.input.mi.dwFlags = new WinDef.DWORD(flags);
        in.input.mi.time = new WinDef.DWORD(0);
        in.input.mi.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        send(in);
    }

    /**
     * One event. {@code SendInput} answers 0 when something blocked it — most often UIPI: a game running as
     * administrator does not take input from a process that is not.
     */
    private static void send(WinUser.INPUT in) {
        WinDef.DWORD sent = com.sun.jna.platform.win32.User32.INSTANCE.SendInput(
                new WinDef.DWORD(1), (WinUser.INPUT[]) in.toArray(1), in.size());
        if (sent.intValue() == 0 && !warnedBlocked) {
            warnedBlocked = true;
            Diag.error("Windows", "SendInput was blocked. A game running as administrator only takes input "
                    + "from a bot that runs as administrator too.");
        }
    }

    private static volatile boolean warnedBlocked;
}
