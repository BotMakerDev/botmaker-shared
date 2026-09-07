package com.botmaker.shared.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The untyped store, over a real {@code activities.json} read off the test classpath.
 *
 * <p>The fixture is a file rather than a string on purpose: {@link ProjectValues#load} is what a bot actually
 * runs, and a test that only ever calls {@link ProjectValues#of} never exercises the missing-resource arm
 * that every empty project takes.
 */
class ProjectValuesTest {

    private static final ProjectValues FIXTURE = ProjectValues.load("/activities-fixture.json");

    @AfterEach
    void forgetAnyStub() {
        ProjectValues.use(null);
    }

    // ---- loading ----------------------------------------------------------------------------------------

    @Test
    void aRealFileOffTheClasspathLoads() {
        assertFalse(FIXTURE.isEmpty());
        assertEquals(List.of("Mining", "Selling"), FIXTURE.activities());
    }

    @Test
    void aMissingResourceIsEmptyRatherThanAFailure() {
        ProjectValues absent = ProjectValues.load("/no-such-file.json");
        assertTrue(absent.isEmpty(), "an empty project has no model and is not misconfigured");
        assertEquals("", absent.one("wait"));
        assertFalse(absent.enabled("Mining"));
    }

    @Test
    void textThatIsNotJsonIsEmptyRatherThanAFailure() {
        assertTrue(ProjectValues.of("{ this is not json").isEmpty());
        assertTrue(ProjectValues.of(null).isEmpty());
        assertTrue(ProjectValues.of("   ").isEmpty());
    }

    @Test
    void currentIsWhateverTheSeamWasGiven() {
        ProjectValues.use(FIXTURE);
        assertEquals("3s500ms", ProjectValues.current().one("wait"));
    }

    // ---- variables --------------------------------------------------------------------------------------

    @Test
    void oneReadsTheFirstValueAndManyReadsThemAll() {
        assertEquals("3s500ms", FIXTURE.one("wait"));
        assertEquals("north", FIXTURE.one("zones"), "one() of a list is its first element");
        assertEquals(List.of("north", "south", "east"), FIXTURE.many("zones"));
        assertEquals(List.of("3s500ms"), FIXTURE.many("wait"), "a plain value is a one-element list");
    }

    @Test
    void anUndeclaredNameIsEmptyEverywhere() {
        assertEquals("", FIXTURE.one("minHelath"));
        assertEquals(List.of(), FIXTURE.many("minHelath"));
        assertFalse(FIXTURE.declares("minHelath"));
        assertEquals("", FIXTURE.typeId("minHelath"));
    }

    @Test
    void declaredButEmptyIsNotTheSameAsUndeclared() {
        assertTrue(FIXTURE.declares("emptyList"), "a list the user has not filled in is still declared");
        assertEquals(List.of(), FIXTURE.many("emptyList"));
        assertEquals("", FIXTURE.one("emptyList"));
    }

    @Test
    void aNullNameIsAnOrdinaryMiss() {
        assertEquals("", FIXTURE.one(null));
        assertEquals(List.of(), FIXTURE.many(null));
        assertFalse(FIXTURE.declares(null));
    }

    @Test
    void theStoredTypeIdIsReadableWithoutResolvingIt() {
        assertEquals("DURATION", FIXTURE.typeId("wait"));
        assertEquals("WHOLE_NUMBER", FIXTURE.typeId("minHealth"));
    }

    @Test
    void everyVariableIsListedInFileOrder() {
        assertEquals(List.of("wait", "minHealth", "zones", "emptyList", "unreadable"), FIXTURE.variables());
    }

    // ---- activities -------------------------------------------------------------------------------------

    @Test
    void anActivityAnswersItsOwnFlags() {
        assertTrue(FIXTURE.enabled("Mining"));
        assertFalse(FIXTURE.enabled("Selling"));
        assertTrue(FIXTURE.goHome("Mining"));
        assertTrue(FIXTURE.popupCheck("Selling"));
        assertEquals(List.of("FOUND", "EMPTY"), FIXTURE.outcomes("Mining"));
        assertEquals(List.of(), FIXTURE.outcomes("Selling"));
    }

    @Test
    void anActivityNothingKnowsAboutIsOff() {
        assertFalse(FIXTURE.enabled("Fishing"), "an activity nothing knows about must not run");
        assertFalse(FIXTURE.enabled(null));
        assertEquals(List.of(), FIXTURE.outcomes("Fishing"));
    }

    // ---- the rest of the file ---------------------------------------------------------------------------

    @Test
    void aSectionIsHandedBackUninterpreted() {
        assertEquals("Mining", FIXTURE.section("flow").path("start").asText(""));
        assertTrue(FIXTURE.section("nothing-by-this-name").isMissingNode());
    }
}
