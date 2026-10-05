package com.botmaker.shared.capture.linux;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Click-through windows: the X11 binding links, and no window is never reported as made click-through. */
class InputTransparentTest {

    @Test
    void noWindowIsNotMadeTransparent() {
        assertFalse(X11Utils.makeInputTransparent(Pointer.NULL, null));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void theShapeExtensionBindsWhereLibXextIsInstalled() {
        // libXext ships with every X11 desktop; a host without it loses only click-through.
        assertNotNull(XShape.instance());
    }
}
