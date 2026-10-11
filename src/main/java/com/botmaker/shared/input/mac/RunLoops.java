package com.botmaker.shared.input.mac;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * The CoreFoundation run loop an event tap delivers on, which jna-platform's {@code CoreFoundation} does not
 * bind. A C {@code Boolean} argument is {@code byte}.
 */
interface RunLoops extends Library {

    RunLoops INSTANCE = Native.load("CoreFoundation", RunLoops.class);

    Pointer CFMachPortCreateRunLoopSource(Pointer allocator, Pointer port, long order);

    void CFMachPortInvalidate(Pointer port);

    Pointer CFRunLoopGetCurrent();

    void CFRunLoopAddSource(Pointer loop, Pointer source, Pointer mode);

    /** Runs the loop for at most {@code seconds}; answers why it returned. */
    int CFRunLoopRunInMode(Pointer mode, double seconds, byte returnAfterSourceHandled);

    void CFRunLoopStop(Pointer loop);

    void CFRelease(Pointer cf);

    /** {@code kCFRunLoopDefaultMode}: a global {@code CFStringRef} variable, not a function. */
    static Pointer defaultMode() {
        return NativeLibrary.getInstance("CoreFoundation").getGlobalVariableAddress("kCFRunLoopDefaultMode")
                .getPointer(0);
    }
}
