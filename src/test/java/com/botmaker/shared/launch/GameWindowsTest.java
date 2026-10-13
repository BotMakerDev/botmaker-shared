package com.botmaker.shared.launch;

import com.botmaker.shared.capture.GenericWindow;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which window is the game's: proved by its class or its process, never by its title — the 2026-10-13 case,
 * Studio's "BotMaker Blocks - Firestone" taken for a closed Firestone.
 */
class GameWindowsTest {

    private static final List<String> TITLES = List.of("Firestone");
    private static final GameWindows.GamePids HOST_77 = new GameWindows.GamePids(Set.of(77L), Set.of());

    private static GenericWindow window(String title, long pid, String windowClass) {
        return new GenericWindow(new Object(), title, new Rectangle(0, 0, 800, 600), pid, windowClass);
    }

    private static final GenericWindow STUDIO =
            window("BotMaker Blocks - Firestone — ~/BotMakerProjects/Firestone", 820053, "com.botmaker.studio.BotMakerStudio");
    private static final GenericWindow WIKI = window("Firestone wiki — Mozilla Firefox", 298589, "org.mozilla.firefox");

    @Test
    void studioNamedAfterAClosedGameIsNeverItsWindow() {
        assertInstanceOf(GameWindows.None.class, GameWindows.judge(List.of(STUDIO), null, GameWindows.GamePids.NONE, TITLES));
        assertInstanceOf(GameWindows.None.class,
                GameWindows.judge(List.of(STUDIO), WindowIdentity.parse("com.botmaker.studio.BotMakerStudio"),
                        GameWindows.GamePids.NONE, TITLES),
                "not even when a bot names Studio's class");
    }

    @Test
    void aWindowOnlyTitledAfterTheGameIsUnsure() {
        GameWindows.Verdict verdict = GameWindows.judge(List.of(STUDIO, WIKI), null, GameWindows.GamePids.NONE, TITLES);
        assertEquals(List.of(WIKI), assertInstanceOf(GameWindows.Unsure.class, verdict).candidates());
    }

    @Test
    void theBotsWindowClassProvesTheWindowWhateverItsTitle() {
        GenericWindow game = window("Firestone v1.2 — Héros", 4242, "firestone.exe");
        GameWindows.Verdict verdict = GameWindows.judge(List.of(WIKI, game), WindowIdentity.parse("Firestone.exe"),
                GameWindows.GamePids.NONE, TITLES);
        assertSame(game, GameWindows.proven(verdict).orElseThrow(), "the class matches whatever the case");
    }

    /** Heroic's Firestone through umu, 2026-10-13: its class is every Steam-less Proton game's. */
    @Test
    void aGenericClassNamesTheGameOnlyWithItsTitle() {
        GenericWindow game = window("Firestone", 541, "steam_app_0");
        GenericWindow other = window("Hades", 612, "steam_app_0");
        GenericWindow discord = window("#tools | Firestone Idle RPG - Discord", 524285, "discord");
        List<GenericWindow> open = List.of(other, discord, STUDIO, game);
        assertSame(game, GameWindows.named(WindowIdentity.parse("steam_app_0|Firestone"), open).orElseThrow());
        assertEquals(Optional.empty(), GameWindows.named(WindowIdentity.parse("steam_app_0|Firestone"),
                List.of(other, discord, STUDIO)));
    }

    @Test
    void theGamesProcessProvesItsWindowAndOthersAreNoCandidates() {
        GenericWindow game = window("Firestone", 77, "steam_app_default");
        GameWindows.Verdict verdict = GameWindows.judge(List.of(WIKI, game), null, HOST_77, TITLES);
        assertSame(game, GameWindows.proven(verdict).orElseThrow());

        GameWindows.Verdict running = GameWindows.judge(List.of(WIKI), null, HOST_77, TITLES);
        assertInstanceOf(GameWindows.None.class, running,
                "while the game runs, a window another known process owns is not a candidate");
    }

    @Test
    void ofSeveralProvedWindowsTheOneTitledAfterTheGameWins() {
        GenericWindow splash = window("Loading", 77, "firestone.exe");
        GenericWindow game = window("Firestone", 77, "firestone.exe");
        assertSame(game, GameWindows.proven(GameWindows.judge(List.of(splash, game), WindowIdentity.parse("firestone.exe"),
                GameWindows.GamePids.NONE, TITLES)).orElseThrow());
    }

    /** Every Flatpak sandbox numbers from 2: another sandboxed app's window with that id is not the game's. */
    @Test
    void aSandboxIdProvesOnlyAWindowTitledAfterTheGame() {
        GameWindows.GamePids sandboxed = new GameWindows.GamePids(Set.of(812345L), Set.of(2L));
        GenericWindow discord = window("Discord", 2, "discord");
        GenericWindow game = window("Firestone", 2, "steam_app_default");
        assertInstanceOf(GameWindows.None.class, GameWindows.judge(List.of(discord), null, sandboxed, TITLES));
        assertSame(game, GameWindows.proven(GameWindows.judge(List.of(discord, game), null, sandboxed, TITLES))
                .orElseThrow());
    }

    @Test
    void aSandboxedProcessIsKnownByItsInnermostId() {
        assertEquals(Optional.of(2L), GameWindows.innermostPid("Name:\twine\nNSpid:\t812345\t2\nPPid:\t1\n"));
        assertEquals(Optional.of(812345L), GameWindows.innermostPid("NSpid:\t812345\n"));
        assertEquals(Optional.empty(), GameWindows.innermostPid("Name:\tbash\n"));
        assertEquals(Optional.empty(), GameWindows.innermostPid("NSpid:\t?\n"), "unreadable, not thrown");
    }
}
