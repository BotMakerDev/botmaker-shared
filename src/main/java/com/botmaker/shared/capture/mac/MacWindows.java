package com.botmaker.shared.capture.mac;

import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFDictionaryRef;
import com.sun.jna.platform.mac.CoreFoundation.CFNumberRef;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the CoreGraphics window list. */
final class MacWindows {

    /**
     * The list's keys, by value: CFDictionaryGetValue compares keys with CFEqual, so a string made here finds
     * the framework's own constant. Made once, on first use, and never released.
     */
    private static final class Keys {
        static final CFStringRef NUMBER = CFStringRef.createCFString("kCGWindowNumber");
        static final CFStringRef PID = CFStringRef.createCFString("kCGWindowOwnerPID");
        static final CFStringRef NAME = CFStringRef.createCFString("kCGWindowName");
        static final CFStringRef OWNER = CFStringRef.createCFString("kCGWindowOwnerName");
        static final CFStringRef BOUNDS = CFStringRef.createCFString("kCGWindowBounds");
        static final CFStringRef LAYER = CFStringRef.createCFString("kCGWindowLayer");
        static final CFStringRef ALPHA = CFStringRef.createCFString("kCGWindowAlpha");
    }

    private MacWindows() {
    }

    /**
     * Ordinary windows, front to back. {@code includeOffscreen} adds the ones not on screen now: minimized, on
     * another Space, or of a hidden app.
     */
    static List<MacWindow> list(boolean includeOffscreen) {
        int option = (includeOffscreen ? CoreGraphics.kCGWindowListOptionAll
                : CoreGraphics.kCGWindowListOptionOnScreenOnly) | CoreGraphics.kCGWindowListExcludeDesktopElements;
        return copy(option, CoreGraphics.kCGNullWindowID).stream().filter(MacWindow::isOrdinary).toList();
    }

    /** The window with this id, if it still exists; asks for that one window, not the whole list. */
    static Optional<MacWindow> byId(long id) {
        return copy(CoreGraphics.kCGWindowListOptionIncludingWindow, (int) id).stream()
                .filter(w -> w.id() == id).findFirst();
    }

    private static List<MacWindow> copy(int option, int relativeTo) {
        CFArrayRef array = CoreGraphics.INSTANCE.CGWindowListCopyWindowInfo(option, relativeTo);
        if (array == null) {
            return List.of();
        }
        try {
            List<MacWindow> windows = new ArrayList<>();
            for (int i = 0; i < array.getCount(); i++) {
                MacWindow w = read(new CFDictionaryRef(array.getValueAtIndex(i)));
                if (w != null) {
                    windows.add(w);
                }
            }
            return windows;
        } finally {
            array.release();
        }
    }

    private static MacWindow read(CFDictionaryRef dict) {
        Pointer number = dict.getValue(Keys.NUMBER);
        Pointer bounds = dict.getValue(Keys.BOUNDS);
        if (number == null || bounds == null) {
            return null;
        }
        CoreGraphics.CGRect rect = new CoreGraphics.CGRect();
        if (CoreGraphics.INSTANCE.CGRectMakeWithDictionaryRepresentation(bounds, rect) == 0) {
            return null;
        }
        rect.read();
        return new MacWindow(
                new CFNumberRef(number).longValue(),
                intOf(dict.getValue(Keys.PID), 0),
                stringOf(dict.getValue(Keys.NAME)),
                stringOf(dict.getValue(Keys.OWNER)),
                new Rectangle2D.Double(rect.x, rect.y, rect.width, rect.height),
                intOf(dict.getValue(Keys.LAYER), 0),
                dict.getValue(Keys.ALPHA) == null ? 1 : new CFNumberRef(dict.getValue(Keys.ALPHA)).doubleValue());
    }

    private static int intOf(Pointer number, int otherwise) {
        return number == null ? otherwise : new CFNumberRef(number).intValue();
    }

    private static String stringOf(Pointer string) {
        if (string == null) {
            return "";
        }
        String s = new CFStringRef(string).stringValue();
        return s == null ? "" : s;
    }
}
