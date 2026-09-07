package com.botmaker.shared.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Settings} over the fixture, with a grammar supplied by the test rather than by the classpath.
 *
 * <p>The grammar here is deliberately crude — {@code Duration.parse}-ish, {@code Integer.parseInt} — because
 * what is under test is the <em>resolution</em>: which reader answers, what an undeclared name does, what
 * unparseable text does, and that a primitive class literal finds the boxed reader. The real spellings are
 * the SDK's {@code WireText} and are tested there.
 */
class SettingsTest {

    /** {@code "3s500ms"} the way {@code WireText} spells it, cut down to what these cases need. */
    private static Duration parseDuration(String stored) {
        long total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(ms|s|m|h)").matcher(stored);
        boolean any = false;
        while (m.find()) {
            any = true;
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "ms" -> n;
                case "s" -> n * 1000;
                case "m" -> n * 60_000;
                default -> n * 3_600_000;
            };
        }
        if (!any) throw new IllegalArgumentException(stored);
        return Duration.ofMillis(total);
    }

    private static final ValueGrammar GRAMMAR = () -> List.of(
            new ValueGrammar.Reader<>(Duration.class, SettingsTest::parseDuration,
                    d -> d.toMillis() + "ms", Duration.ZERO),
            new ValueGrammar.Reader<>(Integer.class, Integer::parseInt, String::valueOf, 0),
            new ValueGrammar.Reader<>(String.class, s -> s, s -> s, ""));

    @BeforeEach
    void useTheFixture() {
        ProjectValues.use(ProjectValues.load("/activities-fixture.json"));
        Settings.use(List.of(GRAMMAR));
    }

    @AfterEach
    void putItBack() {
        ProjectValues.use(null);
        Settings.use(null);
    }

    // ---- the ordinary path ------------------------------------------------------------------------------

    @Test
    void aDeclaredValueIsParsedByItsTypesReader() {
        assertEquals(Duration.ofMillis(3500), Settings.load("wait", Duration.class));
        assertEquals(40, Settings.load("minHealth", Integer.class));
    }

    @Test
    void aPrimitiveClassLiteralFindsTheBoxedReader() {
        assertEquals(40, Settings.load("minHealth", int.class),
                "a bot writes int.class because the field it assigns to is an int");
        assertEquals(Settings.load("minHealth", Integer.class), Settings.load("minHealth", int.class));
    }

    @Test
    void loadAllReadsEveryElementInFileOrder() {
        assertEquals(List.of("north", "south", "east"), Settings.loadAll("zones", String.class));
    }

    @Test
    void loadOfAListAnswersItsFirstElement() {
        assertEquals("north", Settings.load("zones", String.class));
    }

    // ---- every failure is total -------------------------------------------------------------------------

    @Test
    void anUndeclaredNameIsTheTypesOwnFallback() {
        assertEquals(Duration.ZERO, Settings.load("minHelath", Duration.class));
        assertEquals(0, Settings.load("minHelath", int.class));
        assertEquals("", Settings.load("minHelath", String.class));
        assertEquals(List.of(), Settings.loadAll("minHelath", String.class));
    }

    @Test
    void textThatWillNotParseIsTheTypesOwnFallback() {
        assertEquals(Duration.ZERO, Settings.load("unreadable", Duration.class),
                "a hand-edited file must not stop a bot starting");
    }

    @Test
    void aNameDeclaredAsAnotherTypeIsTheFallbackToo() {
        assertEquals(0, Settings.load("wait", int.class), "\"3s500ms\" is not a number");
    }

    @Test
    void aDeclaredButEmptyListIsEmptyRatherThanAFallbackElement() {
        assertEquals(List.of(), Settings.loadAll("emptyList", String.class));
    }

    @Test
    void declaresTellsAZeroApartFromAnAbsence() {
        assertTrue(Settings.declares("minHealth"));
        assertFalse(Settings.declares("minHelath"));
    }

    @Test
    void enabledNeedsNoGrammarAtAll() {
        Settings.use(List.of());
        assertTrue(Settings.enabled("Mining"));
        assertFalse(Settings.enabled("Selling"));
        assertFalse(Settings.enabled("Fishing"));
    }

    // ---- the one thing that throws ----------------------------------------------------------------------

    @Test
    void aTypeNoGrammarClaimsIsAPackagingErrorAndSaysSo() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Settings.load("wait", java.time.LocalTime.class));
        assertTrue(e.getMessage().contains("java.time.LocalTime"), e.getMessage());
        assertTrue(e.getMessage().contains("META-INF/services"), "the message says how to fix it");
        assertTrue(e.getMessage().contains("java.time.Duration"), "and what is available instead");
    }

    @Test
    void twoGrammarsClaimingOneTypeIsRefusedRatherThanResolvedByOrder() {
        ValueGrammar rival = () -> List.of(
                new ValueGrammar.Reader<>(Duration.class, s -> Duration.ofDays(1), Object::toString, Duration.ZERO));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Settings.use(List.of(GRAMMAR, rival)));
        assertTrue(e.getMessage().contains("java.time.Duration"), e.getMessage());
    }

    // ---- the reader contract ----------------------------------------------------------------------------

    @Test
    void aReaderMustNameABoxedTypeAndHaveAFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValueGrammar.Reader<>(int.class, Integer::parseInt, String::valueOf, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ValueGrammar.Reader<>(Integer.class, Integer::parseInt, String::valueOf, null));
    }

    @Test
    void aReaderThatThrowsOrAnswersNullStillReadsAsTheFallback() {
        ValueGrammar.Reader<String> hostile = new ValueGrammar.Reader<>(
                String.class, s -> { throw new IllegalStateException("no"); }, s -> s, "fallback");
        assertEquals("fallback", hostile.read("anything"));

        ValueGrammar.Reader<String> nullish = new ValueGrammar.Reader<>(
                String.class, s -> null, s -> s, "fallback");
        assertEquals("fallback", nullish.read("anything"));
        assertEquals("fallback", nullish.read(null));
    }
}
