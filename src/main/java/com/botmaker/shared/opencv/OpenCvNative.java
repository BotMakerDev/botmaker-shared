package com.botmaker.shared.opencv;

import nu.pattern.OpenCV;

/**
 * Idempotent OpenCV native loader — the one place in the process that extracts and loads the OpenPnP
 * native library.
 *
 * <p>There used to be three of these: one in the SDK's {@code internal.opencv}, one in Studio's
 * {@code ui.app.capture} (whose javadoc admitted it mirrored the SDK's), and a third inside shared's own
 * {@code ocr.OcrNative}. All three pin the same {@code org.openpnp:opencv} and all three can run in one JVM,
 * each with its own {@code loaded} flag — so nothing stopped the extraction from happening more than once.
 * Loading a native library is exactly the kind of process-global thing that must be single-sourced.
 *
 * <p>Call {@link #ensureLoaded()} from a {@code static {}} block on any class that links an
 * {@code org.opencv} type, before it is first touched.
 */
public final class OpenCvNative {

    private OpenCvNative() {}

    private static boolean loaded = false;

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        // OpenPnP handles extracting and loading the correct OS native library automatically.
        OpenCV.loadLocally();
        loaded = true;
    }
}
