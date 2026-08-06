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
    void anEmulatorCaptureSourceYieldsItsInstanceName() {
        assertEquals("Waydroid", ProjectProperties.emulatorInstanceOf("emulator:Waydroid"));
        // Studio writes the spec from a picked instance name, which can carry spaces on some products.
        assertEquals("MuMu Player 12", ProjectProperties.emulatorInstanceOf("  emulator:MuMu Player 12  "));
    }

    @Test
    void everyOtherCaptureSourceFormIsNotAnEmulator() {
        // The other three grammar forms, the degenerate prefix-with-no-name, and an unset key.
        assertNull(ProjectProperties.emulatorInstanceOf("desktop"));
        assertNull(ProjectProperties.emulatorInstanceOf("monitor:1"));
        assertNull(ProjectProperties.emulatorInstanceOf("window:Firestone"));
        assertNull(ProjectProperties.emulatorInstanceOf("emulator:"));
        assertNull(ProjectProperties.emulatorInstanceOf("emulator:   "));
        assertNull(ProjectProperties.emulatorInstanceOf(null));
    }

    @Test
    void theBooleanVocabularyIsTheSameOffTheKeyAsOnIt() {
        // The SDK's session overrides arrive from a system property and the environment, not from this file,
        // and used to carry their own copy of this switch.
        for (String on : new String[]{"true", "1", "yes", "on", " ON ", "True"}) {
            assertTrue(ProjectProperties.parseBoolean(on), on + " must read as on");
        }
        for (String off : new String[]{"false", "0", "no", "off", "OFF"}) {
            assertFalse(ProjectProperties.parseBoolean(off), off + " must read as off");
        }
        assertNull(ProjectProperties.parseBoolean("maybe"));
        assertNull(ProjectProperties.parseBoolean("  "));
        assertNull(ProjectProperties.parseBoolean(null));
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
