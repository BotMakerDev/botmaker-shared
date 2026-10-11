package com.botmaker.shared.capture.mac;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

/**
 * The slice of CoreGraphics the macOS controller calls: the window list, window images, displays, and events.
 *
 * <p>Every one is a plain C function, so JNA reaches it without the Objective-C runtime. A C {@code bool}
 * <em>return</em> is mapped to {@code byte}: JNA's {@code boolean} reads a whole int, and only the low byte of a
 * {@code bool} return is defined. {@code size_t} is {@code long} (macOS is LP64 on both arches).
 *
 * <p>Coordinates are CoreGraphics' global <b>points</b>, top-left origin at the main display's corner. The
 * controller speaks pixels; {@link MacDisplays} converts.
 *
 * <p>Public for {@code input.mac.MacInputListener}, which reads events off a tap with the same calls; one
 * binding per library, so a mapping fixed here is fixed for both.
 */
public interface CoreGraphics extends Library {

    CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

    @Structure.FieldOrder({"x", "y"})
    class CGPoint extends Structure implements Structure.ByValue {
        public double x;
        public double y;

        public CGPoint() {
        }

        CGPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /** {@code CGRect} is {@code {{x, y}, {width, height}}}, laid out as four doubles. */
    @Structure.FieldOrder({"x", "y", "width", "height"})
    class CGRect extends Structure {
        public double x;
        public double y;
        public double width;
        public double height;

        public CGRect() {
        }

        CGRect(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        static class ByValue extends CGRect implements Structure.ByValue {
            public ByValue() {
            }

            ByValue(double x, double y, double width, double height) {
                super(x, y, width, height);
            }
        }

        /** {@code CGRectNull}: "the bounds of whatever is captured", as {@code CGWindowListCreateImage} reads it. */
        static ByValue nullRect() {
            return new ByValue(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0);
        }
    }

    // --- Window list ---

    int kCGWindowListOptionAll = 0;
    int kCGWindowListOptionOnScreenOnly = 1;
    int kCGWindowListOptionIncludingWindow = 1 << 3;
    int kCGWindowListExcludeDesktopElements = 1 << 4;
    int kCGNullWindowID = 0;

    /** Front to back. Window names are only filled in for other apps once Screen Recording is granted. */
    CFArrayRef CGWindowListCopyWindowInfo(int option, int relativeToWindow);

    byte CGRectMakeWithDictionaryRepresentation(Pointer dict, CGRect rect);

    // --- Window images ---

    int kCGWindowImageBoundsIgnoreFraming = 1;
    int kCGWindowImageBestResolution = 1 << 3;

    /**
     * Deprecated since macOS 14 in favour of ScreenCaptureKit, which has no C API. It still answers at run time;
     * reached through {@code dlsym}, the SDK's availability marking does not apply.
     */
    Pointer CGWindowListCreateImage(CGRect.ByValue screenBounds, int listOption, int windowId, int imageOption);

    long CGImageGetWidth(Pointer image);

    long CGImageGetHeight(Pointer image);

    void CGImageRelease(Pointer image);

    Pointer CGColorSpaceCreateDeviceRGB();

    void CGColorSpaceRelease(Pointer space);

    /** 32-bit little-endian words with the alpha byte skipped: in memory, {@code 0x00RRGGBB} ints. */
    int BITMAP_XRGB_LITTLE = 6 /* kCGImageAlphaNoneSkipFirst */ | (2 << 12) /* kCGBitmapByteOrder32Little */;

    Pointer CGBitmapContextCreate(Pointer data, long width, long height, long bitsPerComponent, long bytesPerRow,
                                  Pointer space, int bitmapInfo);

    void CGContextDrawImage(Pointer context, CGRect.ByValue rect, Pointer image);

    void CGContextRelease(Pointer context);

    // --- Displays ---

    int CGGetActiveDisplayList(int maxDisplays, int[] displays, IntByReference count);

    CGRect.ByValue CGDisplayBounds(int display);

    Pointer CGDisplayCopyDisplayMode(int display);

    long CGDisplayModeGetWidth(Pointer mode);

    long CGDisplayModeGetPixelWidth(Pointer mode);

    void CGDisplayModeRelease(Pointer mode);

    // --- Events ---

    int kCGHIDEventTap = 0;

    int kCGEventLeftMouseDown = 1;
    int kCGEventLeftMouseUp = 2;
    int kCGEventRightMouseDown = 3;
    int kCGEventRightMouseUp = 4;
    int kCGEventMouseMoved = 5;
    int kCGEventLeftMouseDragged = 6;
    int kCGEventRightMouseDragged = 7;
    int kCGEventOtherMouseDown = 25;
    int kCGEventOtherMouseUp = 26;
    int kCGEventOtherMouseDragged = 27;
    /** A modifier key's press or release: apps watch this, not a key down, to see Shift and friends. */
    int kCGEventFlagsChanged = 12;

    int kCGMouseEventClickState = 1;
    int kCGMouseEventDeltaX = 4;
    int kCGMouseEventDeltaY = 5;
    /**
     * The window a mouse event is for. Set on an event posted to a process, AppKit routes it to that window
     * rather than hit-testing the screen, where another app's window may cover the point.
     */
    int kCGMouseEventWindowUnderMousePointer = 91;
    int kCGMouseEventWindowUnderMousePointerThatCanHandleThisEvent = 92;

    int kCGScrollEventUnitLine = 1;

    Pointer CGEventCreate(Pointer source);

    CGPoint CGEventGetLocation(Pointer event);

    void CGEventSetLocation(Pointer event, CGPoint location);

    Pointer CGEventCreateMouseEvent(Pointer source, int mouseType, CGPoint location, int mouseButton);

    Pointer CGEventCreateKeyboardEvent(Pointer source, short virtualKey, boolean keyDown);

    /** {@code UniChar} is 16 bits: {@code short}, since JNA maps a Java {@code char[]} to 32-bit {@code wchar_t}. */
    void CGEventKeyboardSetUnicodeString(Pointer event, long length, short[] unicodeString);

    void CGEventSetType(Pointer event, int type);

    long CGEventGetFlags(Pointer event);

    /** The non-variadic form; the variadic {@code CGEventCreateScrollWheelEvent} is not safe through JNA on arm64. */
    Pointer CGEventCreateScrollWheelEvent2(Pointer source, int units, int wheelCount, int wheel1, int wheel2,
                                           int wheel3);

    void CGEventSetIntegerValueField(Pointer event, int field, long value);

    void CGEventSetFlags(Pointer event, long flags);

    /** Real input, as from the devices: moves the cursor and lands on whatever is on top. */
    void CGEventPost(int tap, Pointer event);

    /** Into one process's event queue: the cursor stays put and the app need not be in front. */
    void CGEventPostToPid(int pid, Pointer event);

    // --- Event taps (the input listener) ---

    /** The session tap: every event of the user's login session, after the window server routes it. */
    int kCGSessionEventTap = 1;
    int kCGHeadInsertEventTap = 0;
    /** Observes only: the tap can neither change nor swallow an event. */
    int kCGEventTapOptionListenOnly = 1;
    /** Pseudo-events a tap gets when macOS turned it off; it is turned back on. */
    int kCGEventTapDisabledByTimeout = 0xFFFFFFFE;
    int kCGEventTapDisabledByUserInput = 0xFFFFFFFF;

    int kCGEventKeyDown = 10;
    int kCGEventKeyUp = 11;
    int kCGEventScrollWheel = 22;

    int kCGMouseEventButtonNumber = 3;
    int kCGKeyboardEventKeycode = 9;
    int kCGScrollWheelEventDeltaAxis1 = 11;
    /** 1 for a trackpad or Magic Mouse scroll, which comes as many small pixel steps instead of lines. */
    int kCGScrollWheelEventIsContinuous = 88;
    int kCGScrollWheelEventPointDeltaAxis1 = 96;
    /** Non-zero while a scroll coasts on after the fingers left the trackpad. */
    int kCGScrollWheelEventMomentumPhase = 123;

    interface TapCallback extends Callback {
        /** Answers the event; a listen-only tap's answer is ignored, but it must not be null. */
        Pointer invoke(Pointer proxy, int type, Pointer event, Pointer userInfo);
    }

    Pointer CGEventTapCreate(int tap, int place, int options, long eventsOfInterest, TapCallback callback,
                             Pointer userInfo);

    void CGEventTapEnable(Pointer tap, boolean enable);

    long CGEventGetIntegerValueField(Pointer event, int field);

    /** {@code UniCharCount} is {@code long}; the buffer is {@code short[]}, as for the setter. */
    void CGEventKeyboardGetUnicodeString(Pointer event, long maxLength, LongByReference actualLength,
                                         short[] buffer);

    // --- Permissions (macOS 10.15+) ---

    byte CGPreflightScreenCaptureAccess();

    byte CGRequestScreenCaptureAccess();

    /** Input Monitoring: what a listen-only event tap needs to see key presses. */
    byte CGPreflightListenEventAccess();

    byte CGRequestListenEventAccess();
}
