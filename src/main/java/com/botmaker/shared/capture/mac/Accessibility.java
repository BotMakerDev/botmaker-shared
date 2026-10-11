package com.botmaker.shared.capture.mac;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation.CFDictionaryRef;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * The Accessibility (AX) C API, for what CoreGraphics cannot do to another app's window: raise it, unminimize it,
 * move it, resize it. The functions live in HIServices; loading the ApplicationServices umbrella finds them
 * through its re-exports. Every call needs the Accessibility grant ({@link MacPermission#ACCESSIBILITY}).
 */
interface Accessibility extends Library {

    Accessibility INSTANCE = Native.load("ApplicationServices", Accessibility.class);

    int kAXErrorSuccess = 0;
    int kAXValueCGPointType = 1;
    int kAXValueCGSizeType = 2;

    byte AXIsProcessTrusted();

    /** With {@code {kAXTrustedCheckOptionPrompt: true}} macOS shows its own "open System Settings" prompt. */
    byte AXIsProcessTrustedWithOptions(CFDictionaryRef options);

    Pointer AXUIElementCreateApplication(int pid);

    int AXUIElementCopyAttributeValue(Pointer element, CFStringRef attribute, PointerByReference value);

    int AXUIElementSetAttributeValue(Pointer element, CFStringRef attribute, Pointer value);

    int AXUIElementPerformAction(Pointer element, CFStringRef action);

    Pointer AXValueCreate(int type, Pointer value);

    /**
     * The CGWindowID behind an AX window. Private, but it is the only bridge from the window list to AX, and
     * every window manager for macOS (yabai, Hammerspoon, Rectangle) relies on it; when it is missing the caller
     * falls back to matching by title.
     */
    int _AXUIElementGetWindow(Pointer element, IntByReference windowId);
}
