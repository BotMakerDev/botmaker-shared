package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starting an app over ADB, against a fake device: what it asks, and what it answers the user. */
class EmulatorAppLauncherTest {

    private static final String PKG = "com.zjcs.android.us";
    private static final String STARTED = "Events injected: 1\n";

    /** A device answering each shell command by {@code shell}, its foreground app {@code foreground}. */
    private record Fake(Function<String, String> answers, String foreground, List<String> asked)
            implements EmulatorAppLauncher.AppDevice {

        Fake(Function<String, String> answers, String foreground) {
            this(answers, foreground, new ArrayList<>());
        }

        @Override
        public String shell(String command) {
            asked.add(command);
            return answers.apply(command);
        }

        @Override
        public String currentApp() {
            return foreground;
        }
    }

    private static Function<String, String> packages(String list, String monkey) {
        return command -> command.equals("pm list packages") ? list
                : command.startsWith("monkey ") ? monkey : "";
    }

    @Test
    void anAppMissingFromTheInstanceIsSaidAndNothingIsStarted() {
        Fake device = new Fake(packages("package:com.android.settings\npackage:com.zjcs.android.us.helper\n", STARTED),
                "");

        EmulatorAppLauncher.Outcome outcome = EmulatorAppLauncher.startOverAdb(device, PKG, "LDPlayer");

        assertFalse(outcome.ok());
        assertEquals(PKG + " isn't installed on LDPlayer. Install it from the game's Install… button, then launch "
                + "again.", outcome.message());
        assertEquals(List.of("pm list packages"), device.asked());
    }

    @Test
    void anInstalledAppIsStartedAndSeenInFront() {
        Fake device = new Fake(packages("package:com.android.settings\npackage:" + PKG + "\n", STARTED), PKG);

        EmulatorAppLauncher.Outcome outcome = EmulatorAppLauncher.startOverAdb(device, " " + PKG + " ", "MEmu");

        assertTrue(outcome.ok());
        assertEquals("Started " + PKG + " on MEmu.", outcome.message());
        assertEquals("monkey -p " + PKG + " -c android.intent.category.LAUNCHER 1", device.asked().get(1));
    }

    @Test
    void aPackageListThatSaysNothingDoesNotStopTheStart() {
        Fake device = new Fake(packages("", STARTED), PKG);

        assertTrue(EmulatorAppLauncher.startOverAdb(device, PKG, "MuMu").ok());
    }

    @Test
    void anAppNoLauncherActivityStartsIsRefusedNotInstalled() {
        Fake device = new Fake(packages("package:" + PKG, "args: [-p, " + PKG + "]"), "");

        EmulatorAppLauncher.Outcome outcome = EmulatorAppLauncher.startOverAdb(device, PKG, "BlueStacks");

        assertFalse(outcome.ok());
        assertTrue(outcome.message().startsWith("Android refused to start " + PKG + " on BlueStacks"),
                outcome.message());
        assertTrue(device.asked().contains("cmd package resolve-activity --brief " + PKG));
    }

    @Test
    void thePackageListIsReadExactly() {
        assertEquals(Boolean.TRUE, EmulatorAppLauncher.installed("package:a.b\r\npackage:" + PKG + "\r\n", PKG));
        assertEquals(Boolean.FALSE, EmulatorAppLauncher.installed("package:" + PKG + ".beta\n", PKG));
        assertNull(EmulatorAppLauncher.installed("", PKG));
        assertNull(EmulatorAppLauncher.installed(null, PKG));
        assertNull(EmulatorAppLauncher.installed("Error: couldn't reach the package manager", PKG));
    }
}
