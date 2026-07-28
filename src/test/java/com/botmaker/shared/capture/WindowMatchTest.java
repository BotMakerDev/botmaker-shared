package com.botmaker.shared.capture;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Ranked window matching: the game beats windows merely named after it, and ties break sensibly. */
class WindowMatchTest {

    private static GenericWindow win(String title, int w, int h) {
        return new GenericWindow(new Object(), title, new Rectangle(0, 0, w, h));
    }

    @Test
    void theGameBeatsWindowsMerelyNamedAfterIt() {
        GenericWindow game = win("Firestone", 1280, 720);
        List<GenericWindow> windows = List.of(
                win("Firestone Wiki — Mozilla Firefox", 1600, 900),
                win("#firestone | Discord", 1400, 800),
                win("Heroic — Firestone", 1000, 700),
                game);
        assertSame(game, WindowMatch.best(windows, "Firestone"),
                "the exact 'Firestone' game window must win over incidental matches");
    }

    @Test
    void exactBeatsPrefixBeatsWholeWordBeatsSubstring() {
        GenericWindow exact = win("Firestone", 800, 600);
        GenericWindow prefix = win("Firestone Idle RPG", 800, 600);
        GenericWindow wholeWord = win("Play Firestone Now", 800, 600);
        GenericWindow substring = win("Bonfirestones", 800, 600);
        List<GenericWindow> ranked = WindowMatch.ranked(
                List.of(substring, wholeWord, prefix, exact), "firestone");
        assertEquals(List.of(exact, prefix, wholeWord, substring), ranked);
    }

    @Test
    void aTrailingDynamicSuffixIsStrippedToBeatAPlainPrefix() {
        GenericWindow withSuffix = win("Firestone: Online Idle RPG", 800, 600);
        GenericWindow prefixOnly = win("Firestone Companion App", 800, 600);
        // Suffix-strip on ": " reduces the first to exactly "Firestone" (tier 1), beating the prefix (tier 2).
        assertSame(withSuffix, WindowMatch.best(List.of(prefixOnly, withSuffix), "firestone"));
    }

    @Test
    void tiesBreakByShortestTitleThenLargestArea() {
        GenericWindow longer = win("Firestone Idle RPG — Deluxe Edition", 1920, 1080);
        GenericWindow shorter = win("Firestone Idle RPG", 800, 600);
        assertSame(shorter, WindowMatch.best(List.of(longer, shorter), "firestone"),
                "same tier → the shorter title is closer to the bare name");

        GenericWindow small = win("Firestone Idle", 640, 480);
        GenericWindow big = win("Firestone Room", 1920, 1080); // same title length as 'small'
        // Equal tier and equal title length → the larger on-screen window wins.
        assertSame(big, WindowMatch.best(List.of(small, big), "firestone"));
    }

    @Test
    void blankTitlesZeroAreaAndNonMatchesAreNotCandidates() {
        assertNull(WindowMatch.best(List.of(win("Firestone", 0, 0)), "firestone"),
                "a zero-area window is not a candidate");
        assertNull(WindowMatch.best(List.of(win("   ", 800, 600)), "firestone"),
                "a blank title is not a candidate");
        assertNull(WindowMatch.best(List.of(win("Some Other Game", 800, 600)), "firestone"),
                "a window that does not contain the needle never matches");
        assertNull(WindowMatch.best(List.of(win("Firestone", 800, 600)), "  "),
                "a blank needle matches nothing");
    }
}
