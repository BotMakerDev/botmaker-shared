package com.botmaker.shared;

import com.botmaker.shared.ipc.TelemetryEvent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

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
 * <p><b>Default: on</b>, matching the SDK, unless the run says otherwise with {@code -Dbotmaker.debug}
 * ({@link #RUN_PROPERTY}, which a host sets from its Debug output toggle). The SDK narrows it at start-up from
 * the bot's settings, where the run property still wins ({@link #runOverride()}).
 *
 * <p><b>The trace.</b> Every line printed here also goes to the {@linkplain #setSink sink}, when one is set, as a
 * {@link TelemetryEvent.Log}: the SDK sets one when a host started the run, so the host shows the line in a trace
 * with its level and source ({@code docs/refactor/40-run-trace.md}). The printing is unchanged by it, so a bot
 * run from a terminal reads exactly as before.
 *
 * <p><b>The source.</b> A line is printed and traced under a source name, {@code [Vision] find ore}. A caller
 * that knows it passes it ({@link #log(String, String, int, TelemetryEvent.Rect)}); the SDK's {@code Debug}
 * passes the name of the class that called it, so no SDK line writes its own. A message that starts with a
 * {@code [Name]} of its own keeps that one, which is how this module's diagnostics name theirs.
 *
 * <p><b>The writer.</b> A traced line also carries the class and method that wrote it, so a host can hide one
 * method's lines ({@link Origin}). The SDK passes it; for every other caller it is the first frame outside this
 * class, looked for only when a sink is set.
 */
public final class Diag {

    /** The run property that forces diagnostics on or off, the same name as the contract's {@code Runs.DEBUG_PROPERTY}. */
    public static final String RUN_PROPERTY = "botmaker.debug";

    /** The longest {@code [Name]} read as a source; a longer bracket is part of the text. */
    private static final int MAX_SOURCE = 32;

    private static volatile boolean enabled = runOverride().orElse(true);
    private static volatile Consumer<TelemetryEvent.Log> sink;

    private Diag() {}

    /** Whether diagnostic output is currently on. Every diagnostic print in {@code shared} consults this. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Sets diagnostic output on or off for the rest of the run. */
    public static void set(boolean on) {
        enabled = on;
    }

    /**
     * What the run's {@link #RUN_PROPERTY} says: {@code true} or {@code false} (any case, trimmed), or empty
     * when it is unset or says anything else, and the bot decides.
     */
    public static Optional<Boolean> runOverride() {
        String value = System.getProperty(RUN_PROPERTY);
        if (value == null) return Optional.empty();
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** Where each line also goes, or {@code null} for nowhere. A sink that throws loses that line, never the bot. */
    public static void setSink(Consumer<TelemetryEvent.Log> lines) {
        sink = lines;
    }

    /** Prints {@code message} to stdout when diagnostics are on; a no-op when off. */
    public static void log(String message) {
        log("", message, 1, null);
    }

    /**
     * Prints {@code message} to stdout when diagnostics are on, and traces it as having happened {@code count}
     * times at {@code where} on the desktop ({@code null} when nowhere in particular).
     */
    public static void log(String message, int count, TelemetryEvent.Rect where) {
        log("", message, count, where);
    }

    /**
     * Prints {@code message} under {@code source} ({@code [source] message}) when diagnostics are on, and traces
     * it with that source, {@code count} times at {@code where}. A {@code message} that starts with its own
     * {@code [Name]} keeps it: an explicit source wins over the one a caller was given.
     */
    public static void log(String source, String message, int count, TelemetryEvent.Rect where) {
        if (enabled) emit(TelemetryEvent.Log.DEBUG, Origin.named(source), message, count, where, null);
    }

    /**
     * Prints and traces {@code message} as written at {@code origin}: under its source, and attributed to its
     * class and method, which a host filters the trace by. For a caller that walked the stack itself (the SDK's
     * {@code Debug}); every other form finds the writer as the first frame outside this class.
     */
    public static void log(Origin origin, String message, int count, TelemetryEvent.Rect where) {
        if (enabled) emit(TelemetryEvent.Log.DEBUG, origin, message, count, where, null);
    }

    /** {@link #error(String, String, Throwable)} as written at {@code origin}; {@code t} may be null. */
    public static void error(Origin origin, String message, Throwable t) {
        if (enabled) emit(TelemetryEvent.Log.ERROR, origin, message, 1, null, t);
    }

    /**
     * Where a line was written: the source name it is printed under ({@code "Vision"}), and the class (binary
     * name) and method that wrote it, each empty when unknown.
     */
    public record Origin(String source, String className, String method) {
        public Origin {
            source = source == null ? "" : source.strip();
            className = className == null ? "" : className;
            method = method == null ? "" : method;
        }

        /** A source name alone; the writer is found from the stack when the line is traced. */
        public static Origin named(String source) {
            return new Origin(source, "", "");
        }
    }

    /** Prints {@code message} to stderr when diagnostics are on; a no-op when off. */
    public static void error(String message) {
        error("", message);
    }

    /**
     * Prints {@code message} to stderr followed by {@code t}'s stack trace, when diagnostics are on. Use this
     * instead of {@code t.printStackTrace()} so a quiet run really is quiet.
     */
    public static void error(String message, Throwable t) {
        error("", message, t);
    }

    /** {@link #error(String)} under {@code source}, the way {@link #log(String, String, int, TelemetryEvent.Rect)} is. */
    public static void error(String source, String message) {
        if (enabled) emit(TelemetryEvent.Log.ERROR, Origin.named(source), message, 1, null, null);
    }

    /** {@link #error(String, Throwable)} under {@code source}. */
    public static void error(String source, String message, Throwable t) {
        if (enabled) emit(TelemetryEvent.Log.ERROR, Origin.named(source), message, 1, null, t);
    }

    private static void emit(String level, Origin origin, String message, int count, TelemetryEvent.Rect where,
                             Throwable t) {
        Origin given = origin == null ? Origin.named("") : origin;
        String text = message == null ? "" : message;
        String source = sourceOf(text);
        if (!source.isEmpty()) {
            text = text.substring(source.length() + 2).stripLeading();
        } else {
            source = given.source();
        }
        String printed = source.isEmpty() ? text : "[" + source + "] " + text;
        boolean isError = TelemetryEvent.Log.ERROR.equals(level);
        (isError ? System.err : System.out).println(printed);
        if (t != null) {
            t.printStackTrace();
            StringWriter stack = new StringWriter();
            t.printStackTrace(new PrintWriter(stack));
            text = text + System.lineSeparator() + stack;
        }
        trace(level, source, given, text, count, where);
    }

    private static void trace(String level, String source, Origin origin, String text, int count,
                              TelemetryEvent.Rect where) {
        Consumer<TelemetryEvent.Log> lines = sink;
        if (lines == null) return;
        try {
            // The writer is looked for only here: a run no host traces never walks the stack for it.
            Origin writer = origin.className().isEmpty() ? writer() : origin;
            lines.accept(new TelemetryEvent.Log(level, source, text, count, System.currentTimeMillis(), where,
                    writer.className(), writer.method(), "", -1));
        } catch (RuntimeException ignored) {
            // A trace is best-effort: the line was printed, and a broken sink must not break the bot.
        }
    }

    private static final StackWalker WALKER = StackWalker.getInstance();

    /** The first frame outside this class: whoever called one of its methods. */
    private static Origin writer() {
        return WALKER.walk(frames -> frames
                        .filter(f -> !f.getClassName().equals(Diag.class.getName()))
                        .findFirst())
                .map(f -> new Origin("", f.getClassName(), f.getMethodName()))
                .orElse(Origin.named(""));
    }

    /** The name in a leading {@code [Name]}, or empty when {@code message} does not start with one. */
    static String sourceOf(String message) {
        if (message == null || !message.startsWith("[")) return "";
        int close = message.indexOf(']');
        if (close < 2 || close > MAX_SOURCE + 1) return "";
        String name = message.substring(1, close);
        return name.isBlank() || !name.strip().equals(name) ? "" : name;
    }
}
