// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.capture;

import com.botmaker.shared.capture.WindowPointer.Clicked;
import com.botmaker.shared.capture.WindowPointer.Pointed;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The clicked window is resolved to one BotMaker can capture, or refused with why. */
class WindowPointerTest {

    private static GenericWindow win(Object handle, String windowClass, String title, Rectangle rect) {
        return new GenericWindow(handle, title, rect, 100, windowClass);
    }

    private static GenericWindow picked(Pointed pointed) {
        return ((Pointed.Window) pointed).window();
    }

    @Test
    void theSameHandleWinsOverEverythingElse() {
        Object handle = new Object();
        GenericWindow game = win(handle, "other", "Other", new Rectangle(0, 0, 10, 10));
        GenericWindow steam = win(new Object(), "steam", "Steam", new Rectangle(0, 0, 800, 600));
        Pointed pointed = WindowPointer.resolve(new Clicked(handle, "steam", "Steam", null),
                List.of(steam, game), WindowPointer.NOT_LISTED);
        assertSame(game, picked(pointed));
    }

    @Test
    void picksByClassAmongTwoInAnyCase() {
        GenericWindow steam = win(new Object(), "steam", "Steam", new Rectangle(0, 0, 800, 600));
        GenericWindow game = win(new Object(), "steam_app_1145350", "Hades II", new Rectangle(0, 0, 2560, 1440));
        Pointed pointed = WindowPointer.resolve(new Clicked(null, "Steam_App_1145350", "whatever", null),
                List.of(steam, game), WindowPointer.NATIVE_WAYLAND);
        assertSame(game, picked(pointed));
    }

    @Test
    void picksByCaptionBetweenTwoOfOneClass() {
        GenericWindow library = win(new Object(), "steam", "Steam", new Rectangle(0, 0, 800, 600));
        GenericWindow friends = win(new Object(), "steam", "Friends List", new Rectangle(0, 0, 800, 600));
        Pointed pointed = WindowPointer.resolve(new Clicked(null, "steam", "Friends List", null),
                List.of(library, friends), WindowPointer.NATIVE_WAYLAND);
        assertSame(friends, picked(pointed));
    }

    @Test
    void picksByGeometryBetweenTwoOfOneClassAndCaption() {
        GenericWindow left = win(new Object(), "wine", "Game", new Rectangle(0, 0, 1280, 720));
        GenericWindow right = win(new Object(), "wine", "Game", new Rectangle(1920, 0, 1280, 720));
        Pointed pointed = WindowPointer.resolve(new Clicked(null, "wine", "Game", new Rectangle(1918, 2, 1280, 720)),
                List.of(left, right), WindowPointer.NATIVE_WAYLAND);
        assertSame(right, picked(pointed));
    }

    @Test
    void aClasslessClickMatchesOnlyAClasslessWindowOfItsCaption() {
        GenericWindow steam = win(new Object(), "steam", "Steam", new Rectangle(0, 0, 800, 600));
        GenericWindow bare = win(new Object(), null, "Bare", new Rectangle(0, 0, 800, 600));
        assertSame(bare, picked(WindowPointer.resolve(new Clicked(null, null, "Bare", null),
                List.of(steam, bare), WindowPointer.NOT_LISTED)));
        assertEquals(new Pointed.Unreadable(WindowPointer.NOT_LISTED), WindowPointer.resolve(
                new Clicked(null, null, "", null), List.of(steam, bare), WindowPointer.NOT_LISTED));
    }

    @Test
    void refusesWhenNoWindowOfItsClassIsCapturable() {
        GenericWindow steam = win(new Object(), "steam", "Steam", new Rectangle(0, 0, 800, 600));
        Pointed pointed = WindowPointer.resolve(new Clicked(null, "org.kde.konsole", "Konsole", null),
                List.of(steam), WindowPointer.NATIVE_WAYLAND);
        assertEquals(new Pointed.Unreadable(WindowPointer.NATIVE_WAYLAND), pointed);
    }
}
