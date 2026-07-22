package com.botmaker.shared;

/**
 * The process-wide diagnostic-output switch, and the <em>only</em> one — the SDK's
 * {@code com.botmaker.sdk.api.Debug} is a thin delegate over this class, so a single toggle governs both
 * modules.
 *
 * <p>The flag lives here rather than in the SDK because {@code shared} cannot depend on the SDK: window
 * enumeration, capture and the Linux input backends all print diagnostics, and a bot that turns debugging off
 * must silence those too. The alternative — a second flag in {@code shared} — would silently diverge from the
 * SDK's the first time only one of them was flipped.
 *
 * <p><b>Default: on</b>, matching the SDK. The SDK narrows it at start-up from the project's {@code debug}
 * key; anything printed before that class loads (in practice nothing, since a bot touches the SDK first)
 * prints under the default.
 */
public final class Diag {

    private static volatile boolean enabled = true;

    private Diag() {}

    /** Whether diagnostic output is currently on. Every diagnostic print in {@code shared} consults this. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Sets diagnostic output on or off for the rest of the run. */
    public static void set(boolean on) {
        enabled = on;
    }

    /** Prints {@code message} to stdout when diagnostics are on; a no-op when off. */
    public static void log(String message) {
        if (enabled) {
            System.out.println(message);
        }
    }

    /** Prints {@code message} to stderr when diagnostics are on; a no-op when off. */
    public static void error(String message) {
        if (enabled) {
            System.err.println(message);
        }
    }

    /**
     * Prints {@code message} to stderr followed by {@code t}'s stack trace, when diagnostics are on. Use this
     * instead of {@code t.printStackTrace()} so a quiet run really is quiet.
     */
    public static void error(String message, Throwable t) {
        if (enabled) {
            System.err.println(message);
            t.printStackTrace();
        }
    }
}
