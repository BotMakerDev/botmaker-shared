package com.botmaker.shared.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed accessors over {@code botmaker-project.properties}, exercised through the {@code setForTesting}
 * seam so both the "key absent" defaults and explicit values are covered without a classpath resource.
 */
class ProjectPropertiesTest {

    @AfterEach
    void reset() {
        ProjectProperties.setForTesting(null);
    }

    private static void with(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        ProjectProperties.setForTesting(p);
    }

    @Test
    void sessionIsolatedDefaultsToTrueWhenAbsent() {
        ProjectProperties.setForTesting(new Properties());
        assertTrue(ProjectProperties.sessionIsolated());
    }

    @Test
    void sessionIsolatedHonoursAnExplicitFalse() {
        with(ProjectProperties.KEY_SESSION_ISOLATED, "false");
        assertFalse(ProjectProperties.sessionIsolated());
    }

    @Test
    void sessionIsolatedTrueIsAlsoAccepted() {
        with(ProjectProperties.KEY_SESSION_ISOLATED, "true");
        assertTrue(ProjectProperties.sessionIsolated());
    }

    @Test
    void sessionIsolatedUnparseableFallsBackToTheTrueDefault() {
        with(ProjectProperties.KEY_SESSION_ISOLATED, "maybe");
        assertTrue(ProjectProperties.sessionIsolated());
    }

    @Test
    void sessionBackendIsRawOrNull() {
        ProjectProperties.setForTesting(new Properties());
        assertNull(ProjectProperties.sessionBackend());
        with(ProjectProperties.KEY_SESSION_BACKEND, "gamescope");
        assertEquals("gamescope", ProjectProperties.sessionBackend());
    }

    @Test
    void debugStillParsesTrueFalseAndNull() {
        ProjectProperties.setForTesting(new Properties());
        assertNull(ProjectProperties.debug());
        with(ProjectProperties.KEY_DEBUG, "off");
        assertFalse(ProjectProperties.debug());
        with(ProjectProperties.KEY_DEBUG, "on");
        assertTrue(ProjectProperties.debug());
    }
}
