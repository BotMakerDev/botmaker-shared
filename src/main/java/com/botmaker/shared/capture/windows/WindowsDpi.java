package com.botmaker.shared.capture.windows;

import com.botmaker.shared.Diag;
import com.sun.jna.Pointer;

/**
 * Keeps every Win32 coordinate this package reads or writes in <b>physical pixels</b>.
 *
 * <p>Windows lies to a process that has not said it handles scaling: on a 150% screen a DPI-unaware process is
 * told a 2560×1440 monitor is 1707×960, and {@code GetWindowRect}, {@code ClientToScreen} and
 * {@code SetCursorPos} all speak that scaled space while a {@code PrintWindow} frame comes back at full size.
 * The window rect, the frame and the click then disagree by the scale factor. A per-monitor aware caller gets
 * the physical numbers everywhere, which is what the rest of the codebase assumes
 * ({@code ScreenGeometry}: device pixels).
 *
 * <p>Two layers, because the process may not be ours to set:
 * <ul>
 *   <li>{@link #declareProcessAware()} asks for per-monitor v2 once, at start-up. It fails whenever the
 *       awareness is already fixed — the JDK's own {@code java.exe} manifest declares per-monitor, and AWT or
 *       JavaFX set it on first use — which is fine: any per-monitor awareness gives physical coordinates.</li>
 *   <li>{@link #physical()} switches the calling thread to per-monitor v2 for the length of one call and puts it
 *       back. It is what makes the coordinates physical even in a process that ended up unaware (a launcher
 *       without the manifest), and it never leaves a toolkit thread changed.</li>
 * </ul>
 * Both need Windows 10 (1607 for the thread, 1703 for the process); on anything older they do nothing.
 */
public final class WindowsDpi {

    /** {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2}, a pseudo-handle. */
    private static final Pointer PER_MONITOR_V2 = new Pointer(-4);

    private static final int PER_MONITOR = 2;

    private static volatile boolean threadScopeAvailable = true;

    private WindowsDpi() {}

    /**
     * Ask Windows for per-monitor v2 awareness for the whole process, and say once what the process ended up
     * with. Safe to call more than once and from any thread.
     */
    static void declareProcessAware() {
        try {
            boolean set = User32.INSTANCE.SetProcessDpiAwarenessContext(PER_MONITOR_V2);
            int awareness = User32.INSTANCE.GetAwarenessFromDpiAwarenessContext(
                    User32.INSTANCE.GetThreadDpiAwarenessContext());
            if (!set && awareness != PER_MONITOR) {
                Diag.log("[Windows] the process is DPI " + awarenessName(awareness)
                        + "; window coordinates are read per-monitor aware call by call instead");
            }
        } catch (UnsatisfiedLinkError older) {
            Diag.log("[Windows] no per-monitor DPI awareness before Windows 10 1703: on a scaled screen, "
                    + "clicks and captures may disagree by the scale factor");
        }
    }

    private static String awarenessName(int awareness) {
        return switch (awareness) {
            case 0 -> "unaware";
            case 1 -> "system aware";
            case PER_MONITOR -> "per-monitor aware";
            default -> "awareness unknown";
        };
    }

    /**
     * Run the caller's Win32 calls per-monitor aware until {@link Scope#close()}:
     * {@code try (var dpi = WindowsDpi.physical()) { … }}.
     */
    public static Scope physical() {
        if (!threadScopeAvailable) {
            return Scope.NONE;
        }
        try {
            Pointer previous = User32.INSTANCE.SetThreadDpiAwarenessContext(PER_MONITOR_V2);
            return previous == null ? Scope.NONE : new Scope(previous);
        } catch (UnsatisfiedLinkError older) {
            threadScopeAvailable = false;
            return Scope.NONE;
        }
    }

    /** One thread's per-monitor stretch; closing it restores what the thread had. Not shared between threads. */
    public static final class Scope implements AutoCloseable {

        private static final Scope NONE = new Scope(null);

        private final Pointer previous;

        private Scope(Pointer previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous != null) {
                User32.INSTANCE.SetThreadDpiAwarenessContext(previous);
            }
        }
    }
}
