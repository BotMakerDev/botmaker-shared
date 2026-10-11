package com.botmaker.shared.capture.mac;

import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFMutableDictionaryRef;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;
import com.sun.jna.platform.mac.CoreFoundation.CFTypeRef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.function.Consumer;

/** Another app's window through Accessibility: raise and unminimize it, move it, resize it. */
final class MacAx {

    /** Attribute and action names, made once on first use and never released. */
    private static final class Names {
        static final CFStringRef WINDOWS = CFStringRef.createCFString("AXWindows");
        static final CFStringRef TITLE = CFStringRef.createCFString("AXTitle");
        static final CFStringRef POSITION = CFStringRef.createCFString("AXPosition");
        static final CFStringRef SIZE = CFStringRef.createCFString("AXSize");
        static final CFStringRef MINIMIZED = CFStringRef.createCFString("AXMinimized");
        static final CFStringRef MAIN = CFStringRef.createCFString("AXMain");
        static final CFStringRef FRONTMOST = CFStringRef.createCFString("AXFrontmost");
        static final CFStringRef RAISE = CFStringRef.createCFString("AXRaise");
    }

    private static final class Booleans {
        static final Pointer TRUE = global("CoreFoundation", "kCFBooleanTrue");
        static final Pointer FALSE = global("CoreFoundation", "kCFBooleanFalse");
    }

    private MacAx() {
    }

    // `name` below is the window list's raw window name, for the fallback match in withWindow.

    /** Brings the app to the front, unminimizes the window and raises it above the app's others. */
    static void focus(MacWindow.Ref ref, String name) {
        Pointer app = Accessibility.INSTANCE.AXUIElementCreateApplication(ref.pid());
        if (app == null) {
            return;
        }
        try {
            Accessibility.INSTANCE.AXUIElementSetAttributeValue(app, Names.FRONTMOST, Booleans.TRUE);
            withWindow(app, ref, name, window -> {
                Accessibility.INSTANCE.AXUIElementSetAttributeValue(window, Names.MINIMIZED, Booleans.FALSE);
                Accessibility.INSTANCE.AXUIElementSetAttributeValue(window, Names.MAIN, Booleans.TRUE);
                Accessibility.INSTANCE.AXUIElementPerformAction(window, Names.RAISE);
            });
        } finally {
            Cf.release(app);
        }
    }

    /** Puts the window's top-left corner at {@code (x, y)} points. */
    static void move(MacWindow.Ref ref, String name, double x, double y) {
        setPair(ref, name, Names.POSITION, Accessibility.kAXValueCGPointType, x, y);
    }

    /** Makes the window's frame {@code width × height} points. */
    static void resize(MacWindow.Ref ref, String name, double width, double height) {
        setPair(ref, name, Names.SIZE, Accessibility.kAXValueCGSizeType, width, height);
    }

    /** Asks macOS to show its own Accessibility prompt, which opens System Settings on the right pane. */
    static void promptForTrust() {
        CoreFoundation cf = CoreFoundation.INSTANCE;
        NativeLibrary cfLib = NativeLibrary.getInstance("CoreFoundation");
        CFMutableDictionaryRef options = cf.CFDictionaryCreateMutable(null, new CFIndex(1),
                cfLib.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
                cfLib.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"));
        try {
            cf.CFDictionarySetValue(options,
                    new CFStringRef(global("ApplicationServices", "kAXTrustedCheckOptionPrompt")),
                    new CFTypeRef(Booleans.TRUE));
            Accessibility.INSTANCE.AXIsProcessTrustedWithOptions(options);
        } finally {
            options.release();
        }
    }

    private static void setPair(MacWindow.Ref ref, String name, CFStringRef attribute, int type, double a, double b) {
        Pointer app = Accessibility.INSTANCE.AXUIElementCreateApplication(ref.pid());
        if (app == null) {
            return;
        }
        try {
            withWindow(app, ref, name, window -> {
                Memory pair = new Memory(16);
                pair.setDouble(0, a);
                pair.setDouble(8, b);
                Pointer value = Accessibility.INSTANCE.AXValueCreate(type, pair);
                if (value == null) {
                    return;
                }
                try {
                    Accessibility.INSTANCE.AXUIElementSetAttributeValue(window, attribute, value);
                } finally {
                    Cf.release(value);
                }
            });
        } finally {
            Cf.release(app);
        }
    }

    /**
     * Runs {@code action} on {@code app}'s AX window for {@code ref}: the one whose CGWindowID matches. Only
     * when that private bridge cannot answer does it fall back to the window list's {@code name} — the raw
     * title, never the app name {@link MacWindow#title()} substitutes for a blank one — or, with no name, to
     * the app's only window.
     */
    private static void withWindow(Pointer app, MacWindow.Ref ref, String name, Consumer<Pointer> action) {
        PointerByReference out = new PointerByReference();
        if (Accessibility.INSTANCE.AXUIElementCopyAttributeValue(app, Names.WINDOWS, out)
                != Accessibility.kAXErrorSuccess || out.getValue() == null) {
            return;
        }
        CFArrayRef windows = new CFArrayRef(out.getValue());
        try {
            int count = windows.getCount();
            boolean bridged = false;
            for (int i = 0; i < count; i++) {
                Pointer window = windows.getValueAtIndex(i);
                Long id = windowId(window);
                bridged |= id != null;
                if (id != null && id == ref.id()) {
                    action.accept(window);
                    return;
                }
            }
            if (bridged) {
                return;
            }
            for (int i = 0; i < count; i++) {
                Pointer window = windows.getValueAtIndex(i);
                boolean named = name != null && !name.isBlank();
                if (named ? name.equals(titleOf(window)) : count == 1) {
                    action.accept(window);
                    return;
                }
            }
        } finally {
            windows.release();
        }
    }

    /** The CGWindowID behind an AX window, or {@code null} when the private bridge can't say. */
    private static Long windowId(Pointer window) {
        try {
            IntByReference id = new IntByReference();
            if (Accessibility.INSTANCE._AXUIElementGetWindow(window, id) == Accessibility.kAXErrorSuccess) {
                return Integer.toUnsignedLong(id.getValue());
            }
        } catch (UnsatisfiedLinkError gone) {
            // The private bridge left macOS.
        }
        return null;
    }

    private static String titleOf(Pointer window) {
        PointerByReference out = new PointerByReference();
        if (Accessibility.INSTANCE.AXUIElementCopyAttributeValue(window, Names.TITLE, out)
                != Accessibility.kAXErrorSuccess || out.getValue() == null) {
            return null;
        }
        CFStringRef axTitle = new CFStringRef(out.getValue());
        try {
            return axTitle.stringValue();
        } finally {
            axTitle.release();
        }
    }

    private static Pointer global(String library, String name) {
        return NativeLibrary.getInstance(library).getGlobalVariableAddress(name).getPointer(0);
    }
}
