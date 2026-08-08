package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Waydroid's own app verbs, parsed and argv-built on any machine — no container involved.
 *
 * <p>The reason this class exists at all is that ADB cannot reliably start a Waydroid app: the container
 * freezes itself, and the property that decides which app is <em>rendered</em> is set by Waydroid's launcher
 * and by nothing else. See {@link WaydroidApps} for the full comparison.
 */
class WaydroidAppsTest {

    /** Exactly the shape `waydroid app list` prints, including the app that isn't launchable. */
    private static final String LISTING = """
            Name: Google
            packageName: com.google.android.googlequicksearchbox
            categories:
            \tandroid.intent.category.INFO
            Name: Firestone
            packageName: com.HolydayStudios.Firestone
            categories:
            \tandroid.intent.category.LAUNCHER
            Name: Files
            packageName: com.android.documentsui
            categories:
            \tandroid.intent.category.LAUNCHER
            """;

    @Test
    void everyBlockBecomesAnAppWithItsNameAndLaunchability() {
        List<WaydroidApps.InstalledApp> apps = WaydroidApps.parseList(LISTING);

        assertEquals(3, apps.size());
        assertEquals("com.google.android.googlequicksearchbox", apps.get(0).packageName());
        assertEquals("Google", apps.get(0).label());
        // INFO, not LAUNCHER: present in the listing, not startable — which is the distinction a picker needs.
        assertFalse(apps.get(0).launchable());
        assertEquals("Firestone", apps.get(1).label());
        assertTrue(apps.get(1).launchable());
    }

    @Test
    void aBlockWithNoCategoriesSectionStillYieldsItsPackage() {
        List<WaydroidApps.InstalledApp> apps = WaydroidApps.parseList("Name: Bare\npackageName: com.bare.app\n");

        assertEquals(1, apps.size());
        assertEquals("com.bare.app", apps.get(0).packageName());
        assertFalse(apps.get(0).launchable());
    }

    @Test
    void anAppWithNoNameFallsBackToItsPackageAsTheLabel() {
        List<WaydroidApps.InstalledApp> apps = WaydroidApps.parseList("packageName: com.nameless.app\n");

        assertEquals("com.nameless.app", apps.get(0).label());
    }

    @Test
    void theStoppedSessionErrorTextIsAnEmptyListNotAThrow() {
        // What the CLI prints when it can't reach the container. It is an ordinary answer here: the caller
        // falls back to its cache rather than showing the user a stack trace.
        assertEquals(List.of(), WaydroidApps.parseList("[17:02:11] ERROR: WayDroid session is stopped"));
        assertEquals(List.of(), WaydroidApps.parseList(""));
        assertEquals(List.of(), WaydroidApps.parseList(null));
    }

    @Test
    void aRunningSessionIsLaunchedWithTheBareCommand() {
        // Nothing to host: the session already has its compositor, so this only talks to Waydroid's D-Bus.
        assertEquals(List.of("waydroid", "app", "launch", "com.x"),
                WaydroidApps.launchCommand("com.x", new WaydroidResolution(1280, 720), true, true));
    }

    @Test
    void aStoppedSessionIsLaunchedInsideAGamescopeSizedToTheFramebuffer() {
        // The one command that starts the compositor, the session and the app — with the app as the surface
        // rather than the Android launcher.
        assertEquals(List.of("gamescope", "-W", "1280", "-H", "720", "-w", "1280", "-h", "720",
                        "--expose-wayland", "waydroid", "app", "launch", "com.x"),
                WaydroidApps.launchCommand("com.x", new WaydroidResolution(1280, 720), true, false));
    }

    @Test
    void anUnknownResolutionIsStillSized() {
        // This is the path that actually runs: the size is only read from the container, and this argv is only
        // built when there is no container. Omitting the flags left gamescope scaling Android's framebuffer
        // into its own default output — see WaydroidPlatformTest for the full story.
        String w = Integer.toString(WaydroidResolution.DEFAULT.width());
        String h = Integer.toString(WaydroidResolution.DEFAULT.height());
        assertEquals(List.of("gamescope", "-W", w, "-H", h, "-w", w, "-h", h,
                        "--expose-wayland", "waydroid", "app", "launch", "com.x"),
                WaydroidApps.launchCommand("com.x", null, true, false));
    }

    @Test
    void withoutGamescopeTheBareWaydroidCommandIsUsed() {
        // Works on a real Wayland desktop and fails visibly on X11 — better than being silently unavailable.
        assertEquals(List.of("waydroid", "app", "launch", "com.x"),
                WaydroidApps.launchCommand("com.x", new WaydroidResolution(1280, 720), false, false));
    }
}
