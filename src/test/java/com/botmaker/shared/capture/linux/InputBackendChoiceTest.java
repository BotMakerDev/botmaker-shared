package com.botmaker.shared.capture.linux;

import com.botmaker.shared.capture.linux.input.LinuxInputBackendId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Gates S11 — now discharged.</b> This file was written before {@link LinuxInputBackendId} existed, to pin
 * the behaviour the refactor had to <em>preserve</em>: the wire strings, the precedence between the three
 * sources that supply them, the normalisation applied before matching, and the fact that an unrecognised value
 * falls through to the default rather than throwing. Those assertions now run against the real enum instead of
 * against a transcription of the parse they were guarding.
 *
 * <p>One thing deliberately <em>changed</em>: the old {@code switch} shared a single arm between
 * {@code case "auto"}, {@code case "xsendevent"} and {@code default}, so a typo was indistinguishable from
 * asking for the cursor-safe backend. {@link LinuxInputBackendId#fromId} still resolves it to
 * {@link LinuxInputBackendId#AUTO} — the parse must stay total, per the rule below — but now says so via
 * {@code Diag}. That diagnostic is a side effect and is not asserted here; the resolution is.
 *
 * <p>{@code selectBackend} is private and constructs real X backends, so the precedence ladder is still
 * asserted against a transcription rather than invoked. It is the one part the enum did not absorb.
 */
class InputBackendChoiceTest {

    /**
     * The values {@code selectBackend} switches on. These cross a process boundary — a system property, an
     * environment variable and a Studio-written project key — so they are <b>wire strings</b> and must stay
     * exactly these. Changing one silently reverts a user's saved backend to the default.
     */
    private static final List<String> WIRE_IDS = List.of("xtest", "xdotool", "uinput", "xsendevent");

    /** The fifth accepted value: not a backend, an instruction to pick one. */
    private static final String AUTO = "auto";

    @Test
    void theEnumCoversExactlyTheWireIdsPlusAuto() {
        Set<String> declared = new HashSet<>();
        for (LinuxInputBackendId backend : LinuxInputBackendId.values()) {
            assertTrue(declared.add(backend.id()), "duplicate wire id: " + backend.id());
        }
        Set<String> expected = new HashSet<>(WIRE_IDS);
        expected.add(AUTO);
        assertEquals(expected, declared,
                "the enum is the closed set; adding or dropping a constant changes what a saved project means");
        assertSame(LinuxInputBackendId.AUTO, LinuxInputBackendId.fromId(AUTO),
                "'auto' selects a backend, it does not name one");
    }

    @Test
    void theWireIdsAreLowercaseAndStable() {
        for (LinuxInputBackendId backend : LinuxInputBackendId.values()) {
            String id = backend.id();
            assertEquals(id.toLowerCase(Locale.ROOT), id, "ids are matched after toLowerCase()");
            assertEquals(id.trim(), id, "ids are matched after trim()");
        }
    }

    @Test
    void everyIdRoundTripsThroughFromId() {
        for (LinuxInputBackendId backend : LinuxInputBackendId.values()) {
            assertSame(backend, LinuxInputBackendId.fromId(backend.id()),
                    backend + " must parse back from its own id");
        }
    }

    /**
     * The parse is <b>total</b>: a value from a newer Studio, or a typo in a hand-edited property, yields a
     * working bot on the cursor-safe default rather than an exception on startup. This is the repo's stated
     * rule for closed sets (see the umbrella {@code CLAUDE.md}) and the reason the enum is an improvement
     * rather than a new failure mode.
     */
    @Test
    void anUnrecognisedChoiceFallsThroughToAutoRatherThanThrowing() {
        for (String unknown : List.of("", "  ", "XTEST-ng", "libei", "wayland-portal", "42", "xtets")) {
            assertSame(LinuxInputBackendId.AUTO, LinuxInputBackendId.fromId(unknown),
                    "'" + unknown + "' must resolve to AUTO, and must not silently become a backend it "
                            + "does not name");
        }
        assertSame(LinuxInputBackendId.AUTO, LinuxInputBackendId.fromId(null));
    }

    /** Normalisation is trim-then-lowercase, so these all name the same backend. */
    @Test
    void choicesAreTrimmedAndLowercasedBeforeMatching() {
        for (String spelling : List.of("xtest", "XTest", "  XTEST  ", "\tXtEsT\n")) {
            assertSame(LinuxInputBackendId.XTEST, LinuxInputBackendId.fromId(spelling),
                    "'" + spelling + "' must resolve, or a project key with a stray space stops working");
        }
    }

    /**
     * Precedence, in the order {@code selectBackend} applies it:
     * <ol>
     *   <li>the explicit {@code forced} argument — a bot pin or a session's own choice;</li>
     *   <li>the {@code botmaker.linux.input} system property;</li>
     *   <li>the {@code BOTMAKER_LINUX_INPUT} environment variable;</li>
     *   <li>{@link LinuxInputBackendId#AUTO}.</li>
     * </ol>
     * Note the middle two are <em>not</em> a plain fallback chain: the env var is read first and then passed as
     * the <em>default</em> to {@code getProperty}, so the property wins when both are set. The order is the
     * part that is easy to invert while refactoring and impossible to notice, because the wrong backend still
     * works — it just stops preserving the cursor.
     *
     * <p>{@code forced} is now a {@link LinuxInputBackendId} rather than a string, so "a blank forced value is
     * not a choice" is expressed as {@code null} and enforced by the type rather than by a {@code isBlank()}
     * check.
     */
    @Test
    void thePrecedenceLadderIsPinnedInOrder() {
        assertEquals("botmaker.linux.input", PROPERTY_KEY);
        assertEquals("BOTMAKER_LINUX_INPUT", ENV_KEY);

        assertSame(LinuxInputBackendId.XTEST, resolve(LinuxInputBackendId.XTEST, "uinput", "xdotool"),
                "an explicit choice wins over both");
        assertSame(LinuxInputBackendId.UINPUT, resolve(null, "uinput", "xdotool"),
                "the property wins over the environment");
        assertSame(LinuxInputBackendId.XDOTOOL, resolve(null, null, "xdotool"),
                "the environment is used when no property is set");
        assertSame(LinuxInputBackendId.AUTO, resolve(null, null, null),
                "with nothing set, the choice is auto");
    }

    private static final String PROPERTY_KEY = "botmaker.linux.input";
    private static final String ENV_KEY = "BOTMAKER_LINUX_INPUT";

    /** A transcription of {@code selectBackend}'s choice ladder — the part the enum did not absorb. */
    private static LinuxInputBackendId resolve(LinuxInputBackendId forced, String property, String env) {
        if (forced != null) {
            return forced;
        }
        String fallback = env != null ? env : LinuxInputBackendId.AUTO.id();
        String chosen = property != null ? property : fallback;
        LinuxInputBackendId resolved = LinuxInputBackendId.fromId(chosen);
        assertNotNull(resolved, "fromId never returns null");
        return resolved;
    }
}
