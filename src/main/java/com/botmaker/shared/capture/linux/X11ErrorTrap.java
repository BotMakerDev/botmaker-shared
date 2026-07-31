package com.botmaker.shared.capture.linux;

import com.botmaker.shared.Diag;
import com.sun.jna.Pointer;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs a process-wide Xlib error handler that <b>reports and returns</b>, so a non-fatal X protocol error
 * can never terminate the process.
 *
 * <p><b>Why this is load-bearing, not cosmetic.</b> Xlib's default error handler prints the error and then calls
 * {@code exit(1)}. There is no exception, no stack trace, no {@code hs_err} file — the JVM simply vanishes. The
 * first live gamescope run hit exactly this: {@link LinuxController#captureWindow} fell through to its
 * root-window crop, asked for a rect that hangs 2px off the root (gamescope places the focus window at
 * {@code (2,2)}), and the resulting {@code BadMatch} on {@code X_GetImage} killed the whole bot. That
 * out-of-bounds rect is now fixed at the source, but the class of bug is unbounded — a window unmapping between
 * enumeration and capture produces the same thing on {@code :0} — so the trap is the real guarantee: with it
 * installed the failing call simply returns {@code null} and the caller's fallback ladder continues.
 *
 * <p><b>Reports, but does not spam.</b> Each distinct {@code (error_code, request_code, minor_code)} triple is
 * logged once through {@link Diag}, with Xlib's own decoded text; repeats are silent. That keeps a per-frame
 * capture loop from flooding the log while still naming the first occurrence — the previous incarnation of this
 * class swallowed every error unconditionally, which is why the BadMatch above stayed invisible until it turned
 * fatal.
 *
 * <p><b>Install order matters.</b> Calling {@code XSetErrorHandler} <em>after</em> the JavaFX GTK backend (GDK)
 * has initialized triggers GDK's {@code "XSetErrorHandler() called with a GDK error trap pushed. Don't do
 * that."} warning, so Studio installs it before {@code Application.launch(...)}. {@link LinuxController} also
 * installs it on class load, which covers every bot process (no JavaFX involved) with no caller opting in.
 *
 * <p>Only relevant on Linux/X11; a no-op (caught {@link Throwable}) elsewhere or when libX11 is absent.
 */
public final class X11ErrorTrap {

    // Strong reference kept so the JNA callback is never GC'd while native code holds its pointer.
    private static X11.XErrorHandler handler;
    private static boolean installed;

    /** {@code (error, request, minor)} triples already logged — each is reported once, then stays quiet. */
    private static final Set<Integer> reported = ConcurrentHashMap.newKeySet();

    private X11ErrorTrap() {}

    /** Idempotently installs the trapping handler. Best-effort — never throws. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            handler = X11ErrorTrap::report;
            X11.INSTANCE.XSetErrorHandler(handler);
            installed = true;
        } catch (Throwable ignored) {
            // Non-Linux, headless, or libX11 not present — nothing to trap.
        }
    }

    /** Whether the trap is in place. */
    public static synchronized boolean isInstalled() {
        return installed;
    }

    /**
     * The handler itself: log the first occurrence of each error shape, then return so Xlib unwinds normally
     * (the failing call returns null/0 to its caller) instead of exiting the process.
     */
    private static int report(Pointer display, X11.XErrorEvent event) {
        try {
            int error = event.error_code & 0xFF;
            int request = event.request_code & 0xFF;
            int minor = event.minor_code & 0xFF;
            if (reported.add((error << 16) | (request << 8) | minor)) {
                Diag.error("[Linux/X11] " + describe(display, error) + " on request " + request
                    + (minor == 0 ? "" : "." + minor) + " — trapped, not fatal (first occurrence only)");
            }
        } catch (Throwable ignored) {
            // A handler that throws would be worse than the error it is reporting.
        }
        return 0;
    }

    /** Xlib's own text for an error code ("BadMatch (invalid parameter attributes)"), or just the number. */
    private static String describe(Pointer display, int error) {
        try {
            byte[] buffer = new byte[128];
            X11.INSTANCE.XGetErrorText(display, error, buffer, buffer.length);
            int end = 0;
            while (end < buffer.length && buffer[end] != 0) {
                end++;
            }
            if (end > 0) {
                return new String(buffer, 0, end, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            // fall through to the numeric form
        }
        return "X error " + error;
    }
}
