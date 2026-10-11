package com.botmaker.shared.capture.mac;

import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFTypeRef;

/** The one CoreFoundation release, for the raw {@code Pointer}s the CG and AX bindings return. */
final class Cf {

    private Cf() {
    }

    static void release(Pointer cf) {
        if (cf != null) {
            CoreFoundation.INSTANCE.CFRelease(new CFTypeRef(cf));
        }
    }
}
