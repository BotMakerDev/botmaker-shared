package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A game's readiness off its launcher's files: Steam's manifest flags and remembered sign-in (2026-10-19). */
class GameReadinessTest {

    private static final String SIGNED_IN = """
            "users"
            {
            	"76561198000000000"
            	{
            		"AccountName"		"someone"
            		"RememberPassword"		"1"
            		"AutoLogin"		"1"
            	}
            }
            """;

    @Test
    void aManifestsFlagsSayInstalledUpdatingOrPending() {
        assertEquals(GameReadiness.State.READY, GameReadiness.fromFlags("Dota 2", 4, true).state());
        assertEquals(GameReadiness.State.UPDATE_PENDING, GameReadiness.fromFlags("Dota 2", 6, true).state());
        assertEquals(GameReadiness.State.UPDATE_PENDING, GameReadiness.fromFlags("Dota 2", 4 | 512 | 1024, true)
                .state(), "a paused download goes nowhere by itself");
        assertEquals(GameReadiness.State.UPDATING,
                GameReadiness.fromFlags("Dota 2", 4 | 2 | 1024 | 1048576, true).state());
        assertEquals(GameReadiness.State.UPDATE_PENDING,
                GameReadiness.fromFlags("Dota 2", 4 | 2 | 1024 | 1048576, false).state(),
                "a client closed mid-download leaves its flags; starting the game resumes it");
        assertEquals(GameReadiness.State.UPDATING, GameReadiness.fromFlags("Dota 2", 1026, true).state(),
                "a first install downloading");
        assertEquals(GameReadiness.State.NOT_INSTALLED, GameReadiness.fromFlags("Dota 2", 2, true).state());
        assertEquals(GameReadiness.State.NOT_INSTALLED, GameReadiness.fromFlags("Dota 2", 4 | 2048, true).state());
        assertEquals(GameReadiness.State.UNKNOWN, GameReadiness.fromFlags("Dota 2", -1, true).state());
        assertTrue(GameReadiness.fromFlags("Dota 2", 6, true).reason().contains("Dota 2"));
        assertEquals(1026, GameReadiness.flags("\"AppState\"\n{\n\t\"StateFlags\"\t\t\"1026\"\n}"));
        assertEquals(-1, GameReadiness.flags("no flags"));
    }

    @Test
    void steamIsSignedInOnlyWithARememberedAccount() {
        assertTrue(GameReadiness.signedIn(SIGNED_IN));
        assertTrue(GameReadiness.signedIn("\"users\" { \"1\" { \"AllowAutoLogin\" \"1\" } }"), "the older key");
        assertFalse(GameReadiness.signedIn("\"users\" { \"1\" { \"RememberPassword\" \"0\" \"AutoLogin\" \"0\" } }"));
        assertFalse(GameReadiness.signedIn("\"users\"\n{\n}\n"));
        assertFalse(GameReadiness.signedIn(null));
    }

    @Test
    void aSteamGameIsReadByItsManifestUnderTheSteamRoot(@TempDir Path root) throws IOException {
        LaunchSpec dota = LaunchSpec.parse("steam:570");
        assertEquals(GameReadiness.State.UNKNOWN, GameReadiness.steam(dota, null, false).state(),
                "a Steam we can't find may still take the link");
        assertEquals(GameReadiness.State.SIGNED_OUT, GameReadiness.steam(dota, root, false).state(),
                "no loginusers.vdf");
        assertEquals(GameReadiness.State.NOT_INSTALLED, GameReadiness.steam(dota, root, true).state(),
                "a running client is signed in, remembered or not");

        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/loginusers.vdf"), SIGNED_IN);
        assertEquals(GameReadiness.State.NOT_INSTALLED, GameReadiness.steam(dota, root, false).state());
        assertEquals(GameReadiness.State.READY,
                GameReadiness.steam(LaunchSpec.parse("steam:" + SteamIds.shortcutGameId(42)), root, false).state(),
                "a shortcut has no manifest");

        Files.createDirectories(root.resolve("steamapps"));
        Files.writeString(root.resolve("steamapps/appmanifest_570.acf"),
                "\"AppState\"\n{\n\t\"appid\"\t\t\"570\"\n\t\"name\"\t\t\"Dota 2\"\n\t\"StateFlags\"\t\t\"1030\"\n}\n");
        GameReadiness updating = GameReadiness.steam(dota, root, true);
        assertEquals(GameReadiness.State.UPDATING, updating.state());
        assertEquals("Steam is downloading Dota 2.", updating.reason(), "named by the manifest");
    }

    @Test
    void aRunWaitsOutADownloadAndRefusesWhatCantStart() {
        Iterator<GameReadiness> states = List.of(new GameReadiness(GameReadiness.State.UPDATING, "Downloading."),
                new GameReadiness(GameReadiness.State.UPDATING, "Downloading."), GameReadiness.ready()).iterator();
        List<String> said = new ArrayList<>();
        GameReadiness.awaitLaunchable(states::next, Duration.ofMinutes(1), Duration.ofMillis(1), said::add);
        assertFalse(states.hasNext(), "polled until ready");
        assertEquals(List.of("Downloading. Waiting for it to finish…"), said, "said once a minute");

        IllegalStateException signedOut = assertThrows(IllegalStateException.class, () -> GameReadiness
                .awaitLaunchable(() -> new GameReadiness(GameReadiness.State.SIGNED_OUT, "Sign in."),
                        Duration.ofMinutes(1), Duration.ofMillis(1), s -> { }));
        assertEquals("Sign in.", signedOut.getMessage());
        assertThrows(IllegalStateException.class, () -> GameReadiness.awaitLaunchable(
                () -> new GameReadiness(GameReadiness.State.UPDATING, "Downloading."), Duration.ZERO,
                Duration.ofMillis(1), s -> { }), "a download past the timeout");
    }

    @Test
    void onlyASteamGameHasAnInstallLink() {
        assertEquals(Optional.of("steam://install/570"), GameReadiness.installLink(LaunchSpec.parse("steam:570")));
        assertEquals(Optional.empty(),
                GameReadiness.installLink(LaunchSpec.parse("steam:" + SteamIds.shortcutGameId(42))));
        assertEquals(Optional.empty(), GameReadiness.installLink(LaunchSpec.parse("heroic:Fortnite")));
        assertEquals(Optional.of("steam://open/main"), GameReadiness.signInLink(LaunchKind.STEAM));
        assertEquals(GameReadiness.State.UNKNOWN, GameReadiness.of(LaunchSpec.parse("exe:/a/b")).state());
    }
}
