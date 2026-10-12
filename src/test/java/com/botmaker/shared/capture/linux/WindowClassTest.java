package com.botmaker.shared.capture.linux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@code WM_CLASS} is {@code instance\0class}; the class half names the application, the instance stands in. */
class WindowClassTest {

    @Test
    void theClassHalfIsPreferredAndTheInstanceStandsIn() {
        assertEquals("org.mozilla.firefox", X11Utils.classOf("Navigator\0org.mozilla.firefox"));
        assertEquals("firestone.exe", X11Utils.classOf("firestone.exe\0firestone.exe"));
        assertEquals("steamwebhelper", X11Utils.classOf("steamwebhelper\0"));
        assertNull(X11Utils.classOf(null));
        assertNull(X11Utils.classOf("\0"));
    }
}
