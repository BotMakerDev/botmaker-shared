package com.botmaker.shared.capture.linux;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Gates S11</b>, which replaces {@code LinuxController}'s {@code String backendChoice} with an
 * {@code InputBackendChoice} enum.
 *
 * <p>The enum does not exist yet, so this cannot test it — and testing it after the fact would prove nothing,
 * because the enum would be its own definition of correct. What matters is the behaviour S11 must
 * <em>preserve</em>: the wire strings, the precedence between the three sources that supply them, the
 * normalisation applied before matching, and the fact that an unrecognised value falls through to the default
 * rather than throwing. Those are pinned here, against the current parse, so S11 has something to be checked
 * against rather than reviewed against.
 *
 * <p>{@code selectBackend} is private and constructs real X backends, so the shape is asserted rather than
 * invoked. This is the one place in this module's tests where that is the honest thing to do: the alternative
 * is a seam whose only caller is a test, which S11 would then delete.
 */
class InputBackendChoiceTest {

    /**
     * The four values {@code selectBackend} switches on. These cross a process boundary — a system property, an
     * environment variable and a Studio-written project key — so they are <b>wire strings</b>, and S11's enum
     * must keep them as its {@code id()}s. Changing one silently reverts a user's saved backend to the default.
     */
    private static final List<String> WIRE_IDS = List.of("xtest", "xdotool", "uinput", "xsendevent");

    /** The fifth accepted value: not a backend, an instruction to pick one. */
    private static final String AUTO = "auto";

    @Test
    void theWireIdsAreLowercaseAndStable() {
        for (String id : WIRE_IDS) {
            assertEquals(id.toLowerCase(Locale.ROOT), id, "wire ids are matched after toLowerCase()");
            assertEquals(id.trim(), id, "wire ids are matched after trim()");
        }
        assertEquals(AUTO.toLowerCase(Locale.ROOT), AUTO);
    }

    @Test
    void theWireIdsAreDistinct() {
        assertEquals(WIRE_IDS.size(), WIRE_IDS.stream().distinct().count());
        assertTrue(!WIRE_IDS.contains(AUTO), "'auto' selects a backend, it does not name one");
    }

    /**
     * The parse is <b>total</b>: {@code selectBackend}'s switch has a {@code default} that falls in with
     * {@code auto} and {@code xsendevent}. A value from a newer Studio, or a typo in a hand-edited property,
     * therefore yields a working bot on the cursor-safe backend rather than an exception on startup.
     *
     * <p>S11's {@code fromId} must keep that: total, never throwing, unknown → the same default. This is the
     * repo's stated rule for closed sets (see the umbrella {@code CLAUDE.md}) and the reason the enum is an
     * improvement rather than a new failure mode.
     */
    @Test
    void anUnrecognisedChoiceMustFallThroughToTheDefaultRatherThanThrow() {
        for (String unknown : List.of("", "  ", "XTEST-ng", "libei", "wayland-portal", "42")) {
            String normalised = unknown.trim().toLowerCase(Locale.ROOT);
            boolean matchesABackend = WIRE_IDS.contains(normalised) || AUTO.equals(normalised);
            assertTrue(!matchesABackend || normalised.equals("xtest"),
                    "'" + unknown + "' normalises to '" + normalised + "', which must not silently become a "
                            + "backend it does not name");
        }
    }

    /** Normalisation is trim-then-lowercase, so these all name the same backend. */
    @Test
    void choicesAreTrimmedAndLowercasedBeforeMatching() {
        for (String spelling : List.of("xtest", "XTest", "  XTEST  ", "\tXtEsT\n")) {
            assertEquals("xtest", spelling.trim().toLowerCase(Locale.ROOT),
                    "S11 must normalise before fromId, or a project key with a stray space stops resolving");
        }
    }

    /**
     * Precedence, in the order {@code selectBackend} applies it:
     * <ol>
     *   <li>the explicit {@code forced} argument — a bot pin or a session's own choice;</li>
     *   <li>the {@code botmaker.linux.input} system property;</li>
     *   <li>the {@code BOTMAKER_LINUX_INPUT} environment variable;</li>
     *   <li>{@code auto}.</li>
     * </ol>
     * Note the middle two are <em>not</em> a plain fallback chain: the env var is read first and then passed as
     * the <em>default</em> to {@code getProperty}, so the property wins when both are set. S11 moves this
     * ladder; the order is the part that is easy to invert while refactoring and impossible to notice, because
     * the wrong backend still works — it just stops preserving the cursor.
     */
    @Test
    void thePrecedenceLadderIsPinnedInOrder() {
        assertEquals("botmaker.linux.input", PROPERTY_KEY);
        assertEquals("BOTMAKER_LINUX_INPUT", ENV_KEY);

        assertEquals("xtest", resolve("xtest", "uinput", "xdotool"), "an explicit choice wins over both");
        assertEquals("uinput", resolve(null, "uinput", "xdotool"), "the property wins over the environment");
        assertEquals("xdotool", resolve(null, null, "xdotool"), "the environment is used when no property is set");
        assertEquals(AUTO, resolve(null, null, null), "with nothing set, the choice is auto");
        assertEquals(AUTO, resolve("   ", null, null), "a blank forced value is not a choice");
    }

    private static final String PROPERTY_KEY = "botmaker.linux.input";
    private static final String ENV_KEY = "BOTMAKER_LINUX_INPUT";

    /** A transcription of {@code selectBackend}'s first eight lines — the ladder S11 must move intact. */
    private static String resolve(String forced, String property, String env) {
        if (forced != null && !forced.isBlank()) {
            return forced.trim().toLowerCase(Locale.ROOT);
        }
        String fallback = env != null ? env : AUTO;
        String chosen = property != null ? property : fallback;
        return chosen.trim().toLowerCase(Locale.ROOT);
    }
}
