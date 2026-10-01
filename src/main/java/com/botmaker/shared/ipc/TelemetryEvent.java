package com.botmaker.shared.ipc;

/**
 * A single geometry-only telemetry frame sent from a running bot to the Studio's window-preview panel.
 * Deliberately carries <em>no image bytes</em> — the Studio captures the window frame itself; these events
 * only describe <em>where</em> the vision/interaction functions acted, so frames stay tiny. One kind travels the
 * other way: an {@link Answer} to the bot's {@link Ask}.
 *
 * <p>Lives in {@code botmaker-shared} because it is the single module both the SDK (emitter) and the Studio
 * (consumer) depend on, so the wire vocabulary has one definition. Encoded/decoded by {@link TelemetryFrame}.
 */
public sealed interface TelemetryEvent
        permits TelemetryEvent.Match, TelemetryEvent.Click, TelemetryEvent.Region, TelemetryEvent.Swipe,
                TelemetryEvent.Log, TelemetryEvent.Ask, TelemetryEvent.Answer {

    /** What an event that acts on no surface answers for {@link #target()}: the whole screen. */
    Target NO_SURFACE = new Target(null, 0, 0, 0, 0);

    /** The surface an event refers to, so the Studio can capture the right window/screen. */
    record Target(String title, int x, int y, int width, int height) {}

    /** An axis-aligned rectangle in absolute (virtual-screen) coordinates. */
    record Rect(int x, int y, int width, int height) {}

    /** The surface this event acted on. Never null. */
    Target target();

    /**
     * The 1-based line in the bot's source that triggered this event, or {@code -1} when unknown. Lets the
     * Studio highlight the running block live during a plain run (debug/trace already highlights via JDI).
     */
    int line();

    /**
     * A template match attempt. {@code rect} is the matched bounds (null when {@code !found});
     * {@code region} is the search sub-rectangle (null for a whole-surface search).
     */
    record Match(Target target, Rect region, Rect rect, double confidence, boolean found, int line)
            implements TelemetryEvent {
        /** Line-less convenience (line = {@code -1}). */
        public Match(Target target, Rect region, Rect rect, double confidence, boolean found) {
            this(target, region, rect, confidence, found, -1);
        }
    }

    /** A click landing at ({@code x},{@code y}) absolute. {@code button}: 1=left, 2=middle, 3=right. */
    record Click(Target target, int x, int y, int button, int line) implements TelemetryEvent {
        /** Line-less convenience (line = {@code -1}). */
        public Click(Target target, int x, int y, int button) {
            this(target, x, y, button, -1);
        }
    }

    /**
     * A drag/swipe from ({@code x1},{@code y1}) to ({@code x2},{@code y2}) absolute, over {@code durationMs}.
     *
     * <p>Both ends travel in one event rather than as a stream of moves: the gesture is a single thing the bot
     * decided to do, and a consumer that wants to draw it needs both ends at once anyway. The duration is here
     * because it is the difference between a flick and a slow drag, which nothing about the two points says.
     */
    record Swipe(Target target, int x1, int y1, int x2, int y2, long durationMs, int line)
            implements TelemetryEvent {
        /** Line-less convenience (line = {@code -1}). */
        public Swipe(Target target, int x1, int y1, int x2, int y2, long durationMs) {
            this(target, x1, y1, x2, y2, durationMs, -1);
        }
    }

    /**
     * One line of the bot's debug output, as a host shows it in a trace rather than a console
     * ({@code docs/refactor/40-run-trace.md}). {@code level} is an id ({@link #DEBUG}, {@link #INFO}, {@link #WARN},
     * {@link #ERROR}) and stays a string on the wire, so a level a newer bot adds still reads; {@code source} is
     * the name the line was written under ({@code "Vision"}), empty when it had none; {@code count} is how many
     * times a collapsed line happened, at least 1; {@code rect} is where on the desktop it happened, or null.
     * {@code className} is the binary name of the bot's class whose {@code line} wrote it ({@code
     * com.example.Collect}), empty when unknown: a line number alone names no file, and a bot is several.
     * {@code writerClass} and {@code writerMethod} are the class and method that wrote the line, which a host
     * filters by ({@code com.botmaker.sdk.api.input.Mouse}, {@code click}); for a bot's own line they are
     * the same class as {@code className}. Empty when unknown.
     *
     * <p>A log line acts on no surface, so {@link #target()} is the whole screen: a consumer that draws events
     * by their target draws nothing for it.
     */
    record Log(String level, String source, String text, int count, long atMillis, Rect rect, String writerClass,
               String writerMethod, String className, int line) implements TelemetryEvent {

        public static final String DEBUG = "debug";
        public static final String INFO = "info";
        public static final String WARN = "warn";
        public static final String ERROR = "error";

        /** The longest text a line carries; a longer one (a runaway stack trace) is cut, never dropped. */
        public static final int MAX_TEXT = 16_384;

        public Log {
            level = level == null ? "" : level;
            source = source == null ? "" : source;
            text = text == null ? "" : text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
            count = Math.max(1, count);
            writerClass = writerClass == null ? "" : writerClass;
            writerMethod = writerMethod == null ? "" : writerMethod;
            className = className == null ? "" : className;
        }

        @Override
        public Target target() {
            return NO_SURFACE;
        }

        /** This line attributed to {@code line} of the bot's class {@code className}. */
        public Log at(String className, int line) {
            return new Log(level, source, text, count, atMillis, rect, writerClass, writerMethod, className, line);
        }
    }

    /**
     * The bot asks its user a question and blocks until the host sends an {@link Answer} with the same {@code id}.
     * {@code kind} is an id ({@link Kind#id()}) and stays a string on the wire, so a kind a newer bot adds still
     * reads, as {@link Kind#UNKNOWN}; {@code choices} are the options of a {@link Kind#CHOICE}, empty otherwise.
     * {@code line} is the bot's own line that asked, or {@code -1}.
     *
     * <p>Replaces the {@code BM-INPUT} marker a bot printed on stdout before blocking on stdin (2026-10-01): the
     * host had to scan every line of a run's output for it, could not be told what was asked, and answered on a
     * pipe a terminal run does not have.
     */
    record Ask(long id, String kind, String prompt, java.util.List<String> choices, int line)
            implements TelemetryEvent {

        public Ask {
            kind = kind == null ? "" : kind;
            prompt = prompt == null ? "" : prompt;
            choices = choices == null ? java.util.List.of() : java.util.List.copyOf(choices);
        }

        @Override
        public Target target() {
            return NO_SURFACE;
        }

        /** What the bot wants back; the host draws a different control for each. */
        public enum Kind {
            /** Any line of text. */
            TEXT("text"),
            /** A number, fractions allowed. */
            NUMBER("number"),
            /** A whole number. */
            WHOLE("whole"),
            /** Yes or no, answered {@code "true"} or {@code "false"}. */
            YES_NO("yes-no"),
            /** One of {@link Ask#choices()}, answered as the choice's text. */
            CHOICE("choice"),
            /** A kind this build does not know: asked as text. */
            UNKNOWN("");

            private final String id;

            Kind(String id) {
                this.id = id;
            }

            /** The id on the wire. */
            public String id() {
                return id;
            }

            /** The kind with this id, or {@link #UNKNOWN}. */
            public static Kind fromId(String id) {
                for (Kind kind : values()) {
                    if (kind != UNKNOWN && kind.id.equals(id)) return kind;
                }
                return UNKNOWN;
            }
        }

        /** {@link #kind()} as a constant. */
        public Kind asked() {
            return Kind.fromId(kind);
        }
    }

    /**
     * The host's reply to the {@link Ask} with the same {@code id}, the one frame that travels from the host to
     * the bot. {@code value} is what the user entered, as text, or {@code null} when they cancelled.
     */
    record Answer(long id, String value) implements TelemetryEvent {

        @Override
        public Target target() {
            return NO_SURFACE;
        }

        @Override
        public int line() {
            return -1;
        }
    }

    /** A standalone search-region highlight (a search scoped to a sub-rectangle of the surface). */
    record Region(Target target, Rect rect, int line) implements TelemetryEvent {
        /** Line-less convenience (line = {@code -1}). */
        public Region(Target target, Rect rect) {
            this(target, rect, -1);
        }
    }
}
