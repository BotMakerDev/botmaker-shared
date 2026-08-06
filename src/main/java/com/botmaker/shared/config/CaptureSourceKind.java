package com.botmaker.shared.config;

/**
 * The four forms a {@link ProjectProperties#KEY_CAPTURE_SOURCE} spec can take — {@code desktop},
 * {@code monitor:<index>}, {@code window:<titleSubstring>}, {@code emulator:<instanceName>} — and the one
 * owner of the tokens that separate them.
 *
 * <p>The grammar had exactly one of its four prefixes typed ({@code EMULATOR_SOURCE_PREFIX}, added when the
 * pilot started routing on it) and three spelled as literals wherever someone read or wrote a spec. The SDK's
 * reader went further and paired each literal with a <b>hand-counted substring offset</b> —
 * {@code spec.substring(8)} for {@code "monitor:"}, {@code 7} for {@code "window:"}, {@code 9} for
 * {@code "emulator:"} — so renaming a prefix compiled fine and silently sliced the argument at the wrong
 * character. That is the failure this type removes: {@link #argumentOf} derives the offset from the prefix it
 * matched, and there is no second copy of the prefix to disagree with.
 *
 * <p>Studio writes these specs and the SDK reads them back, which is why the grammar lives in shared next to
 * the key whose value it describes. The parse is total: {@link #of} returns {@code null} for anything it does
 * not recognise, leaving the caller on its own default rather than failing a bot at startup over a typo in a
 * project file.
 */
public enum CaptureSourceKind {

    /** The whole virtual desktop — the one form carrying no argument, so its token <em>is</em> the spec. */
    DESKTOP("desktop", false),
    /** A single monitor by index, as the SDK's {@code Monitor} numbers them. */
    MONITOR("monitor", true),
    /** A window by title substring. */
    WINDOW("window", true),
    /** An Android emulator by instance name — the form the pilot and the launch-target dialog route on. */
    EMULATOR("emulator", true);

    /** What separates a kind from its argument. Part of the persisted format; do not change. */
    private static final String SEPARATOR = ":";

    private final String token;
    private final boolean takesArgument;

    CaptureSourceKind(String token, boolean takesArgument) {
        this.token = token;
        this.takesArgument = takesArgument;
    }

    /**
     * The literal a spec of this kind opens with — {@code "monitor:"}, {@code "window:"},
     * {@code "emulator:"}, or plain {@code "desktop"} for the argument-less form.
     */
    public String prefix() {
        return takesArgument ? token + SEPARATOR : token;
    }

    /** Whether this form carries an argument after its {@code :} at all. Only {@link #DESKTOP} does not. */
    public boolean takesArgument() {
        return takesArgument;
    }

    /** Whether {@code spec} is of this kind, ignoring case and surrounding whitespace. */
    public boolean matches(String spec) {
        if (spec == null) {
            return false;
        }
        String trimmed = spec.trim();
        String prefix = prefix();
        return takesArgument
            ? trimmed.regionMatches(true, 0, prefix, 0, prefix.length())
            : trimmed.equalsIgnoreCase(prefix);
    }

    /**
     * The part after this kind's prefix — the monitor index, window title or instance name — trimmed; or
     * {@code null} when {@code spec} is another kind, when the argument is empty, or when this kind takes
     * none ({@link #DESKTOP}).
     */
    public String argumentOf(String spec) {
        if (!takesArgument || !matches(spec)) {
            return null;
        }
        String argument = spec.trim().substring(prefix().length()).trim();
        return argument.isEmpty() ? null : argument;
    }

    /** The spec that names {@code argument} in this kind's form, e.g. {@code emulator:MuMu Player 12}. */
    public String spec(String argument) {
        return takesArgument ? prefix() + argument : prefix();
    }

    /**
     * The kind {@code spec} is written in, or {@code null} when it is blank or in no recognised form. Total by
     * design — an unreadable value from a hand-edited (or newer) project file must leave the caller on its
     * default, not throw.
     */
    public static CaptureSourceKind of(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        for (CaptureSourceKind kind : values()) {
            if (kind.matches(spec)) {
                return kind;
            }
        }
        return null;
    }
}
