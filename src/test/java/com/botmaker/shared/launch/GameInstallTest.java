package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each launcher's way in, its command lines and its readiness, from fixtures; nothing is installed. */
class GameInstallTest {

    @Test
    void eachKindGetsItsOwnInstallAndTheRestNone() {
        assertInstanceOf(AndroidInstall.class, GameInstall.of(new LaunchSpec(LaunchKind.EMULATOR_APP,
                "com.foo.game@Waydroid"), false));
        assertInstanceOf(GameInstall.Steam.class, GameInstall.of(new LaunchSpec(LaunchKind.STEAM, "570"), false));
        assertInstanceOf(GameInstall.Lutris.class, GameInstall.of(new LaunchSpec(LaunchKind.LUTRIS, "12"), false));
        assertInstanceOf(HeroicInstall.class, GameInstall.of(new LaunchSpec(LaunchKind.HEROIC, "Fortnite"), false));
        GameInstall exe = GameInstall.of(new LaunchSpec(LaunchKind.EXE, "/games/x"), false);
        assertInstanceOf(GameInstall.None.class, exe);
        assertEquals(GameInstall.Outcome.NO_STORE, exe.install(null).outcome());
        assertEquals(GameInstall.Outcome.NO_STORE, exe.update(null).outcome());
    }

    @Test
    void anAndroidSourceNamesApkPureOnlyOnceItIsAllowed() {
        LaunchSpec spec = new LaunchSpec(LaunchKind.EMULATOR_APP, "com.foo.game@Waydroid");
        assertFalse(GameInstall.of(spec, false).source().contains("APKPure"));
        assertTrue(GameInstall.of(spec, true).source().contains("APKPure"));
        assertTrue(GameInstall.of(spec, false).source().contains("goopdl"));
    }

    @Test
    void anAndroidResultSaysWhichSourceServedIt() {
        assertEquals("X is installed on Waydroid. From Google Play's files, through goopdl.",
                AndroidInstall.Source.GOOGLE_PLAY_FILES.said("X is installed on Waydroid."));
        assertEquals("Up to date. From BotMaker's APK library.", AndroidInstall.Source.LIBRARY.said("Up to date"));
    }

    @Test
    void outcomesSayWhetherTheGameIsThere() {
        assertTrue(GameInstall.Outcome.UP_TO_DATE.done());
        assertFalse(GameInstall.Outcome.WAITING_FOR_USER.done());
        assertEquals("waiting for you", GameInstall.Outcome.WAITING_FOR_USER.displayName());
    }

    @Test
    void heroicTellsTheStoreByTheIdAndInstallsEpicThroughLegendary() {
        assertEquals(HeroicInstall.Store.GOG, HeroicInstall.Store.of("1207658924"));
        assertEquals(HeroicInstall.Store.AMAZON, HeroicInstall.Store.of("amzn1.adg.product.abc"));
        assertEquals(HeroicInstall.Store.EPIC, HeroicInstall.Store.of("Fortnite"));
        assertEquals(List.of("/b/legendary", "install", "Fortnite", "-y", "--base-path", "/h/Games/Heroic"),
                HeroicInstall.legendary(Path.of("/b/legendary"), "Fortnite", false, Path.of("/h/Games/Heroic")));
        assertEquals(List.of("/b/legendary", "update", "Fortnite", "-y"),
                HeroicInstall.legendary(Path.of("/b/legendary"), "Fortnite", true, Path.of("/h/Games/Heroic")));
        assertEquals(Path.of("/var/lib/flatpak/app/com.heroicgameslauncher.hgl/current/active/files/bin/heroic/"
                        + "resources/app.asar.unpacked/build/bin/x64/linux/legendary"),
                HeroicInstall.cliCandidates("/h", "legendary").getFirst(), "Flathub's Heroic first");
        assertTrue(HeroicInstall.alreadyCurrent("[cli] INFO: Game is already up to date!"));
        assertFalse(HeroicInstall.alreadyCurrent("[DLManager] INFO: = Progress: 12.00%"));
    }

    @Test
    void lutrisInstallsBySlugOrLibraryIdAndItsReadinessIsTheInstalledList() {
        assertEquals(List.of("lutris", "lutris:install/hollow-knight"),
                LaunchCommands.lutrisInstall("hollow-knight").getFirst());
        assertEquals(List.of("flatpak", "run", "net.lutris.Lutris", "lutris:rungameid/12"),
                LaunchCommands.lutrisInstall("12").get(1));

        LaunchSpec twelve = new LaunchSpec(LaunchKind.LUTRIS, "12");
        LutrisLibrary.Game game = new LutrisLibrary.Game("12", "Hollow Knight", null, "wine");
        assertEquals(GameReadiness.State.READY, GameReadiness.lutris(twelve, Map.of("12", game)).state());
        assertEquals(GameReadiness.State.NOT_INSTALLED,
                GameReadiness.lutris(new LaunchSpec(LaunchKind.LUTRIS, "13"), Map.of("12", game)).state());
        assertEquals(GameReadiness.State.UNKNOWN, GameReadiness.lutris(twelve, Map.of()).state(), "no Lutris");
    }

    @Test
    void anEmulatorAppIsInstalledWhenTheInstancesAppsListIt() {
        LaunchSpec spec = new LaunchSpec(LaunchKind.EMULATOR_APP, "com.foo.game@Waydroid");
        assertEquals(GameReadiness.State.READY, GameReadiness.emulatorApp(spec, "Waydroid",
                List.of("com.android.vending", "com.foo.game"), false).state());
        GameReadiness missing = GameReadiness.emulatorApp(spec, "Waydroid", List.of("com.android.vending"), true);
        assertEquals(GameReadiness.State.NOT_INSTALLED, missing.state());
        assertTrue(missing.reason().contains("Waydroid"), missing.reason());
        assertEquals(GameReadiness.State.UNKNOWN, GameReadiness.emulatorApp(spec, "Waydroid",
                List.of("com.android.vending"), false).state(), "the cache never says missing");
        assertEquals(GameReadiness.State.UNKNOWN,
                GameReadiness.emulatorApp(spec, "Waydroid", List.of(), true).state());
    }

    @Test
    void anInstallHandsOverOnceTheLauncherStartedOrTimeIsUp() {
        GameReadiness notYet = new GameReadiness(GameReadiness.State.NOT_INSTALLED, "no");
        GameReadiness downloading = new GameReadiness(GameReadiness.State.UPDATING, "downloading");
        Iterator<GameReadiness> answers = List.of(notYet, notYet, downloading).iterator();
        assertEquals(downloading, GameInstall.awaitStarted(answers::next, Duration.ofSeconds(5), Duration.ofMillis(1)));
        assertEquals(notYet, GameInstall.awaitStarted(() -> notYet, Duration.ofMillis(20), Duration.ofMillis(5)));
    }
}
