// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.launch;

import com.botmaker.shared.capture.GenericWindow;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What names a game's window on every computer: its {@linkplain GenericWindow#getWindowClass() window class}
 * (X11 {@code WM_CLASS}; a Windows program's file name; a Mac app's name), and its exact title only when that
 * class is {@linkplain #isGeneric shared by unrelated programs}.
 *
 * <p>A class alone is usually enough — Wine names a window after its program ({@code firestone.exe}), Proton
 * after the Steam game ({@code steam_app_1145350}) — and stays the same whatever the title, language or version.
 * But a game Proton runs with no Steam id is {@code steam_app_0}, like every other such game (Heroic's Firestone,
 * 2026-10-13), so its title has to say which one: {@code steam_app_0|Firestone}. A window titled
 * {@code #tools | Firestone Idle RPG - Discord} is still not it.
 *
 * <p>Written as {@link #spec()}: the class, then {@code |} and the title when there is one. The class is never
 * blank.
 */
public record WindowIdentity(String windowClass, String title) {

    /** Between the class and the title in {@link #spec()}; no program file name can hold it. */
    public static final char SEPARATOR = '|';

    /**
     * Classes that name no particular program: a Proton game with no Steam id, Proton's own window before the game
     * names it ({@code steam_proton}, which matched another Proton game open on 2026-11-25), Wine's own desktop, the
     * JVM's AWT frames, and the generic hosts on Windows.
     */
    private static final Set<String> GENERIC = Set.of("steam_app_0", "steam_app_default", "steam_proton", "wine",
            "explorer.exe", "sun-awt-x11-xframepeer", "java", "java.exe", "javaw.exe", "applicationframehost.exe");

    /**
     * A number in a title, with its separators ({@code 59.9}, {@code 12:04}), or a run of white space: what
     * {@link #sameGame} and {@link #matches} leave out.
     */
    private static final Pattern NUMBERS_AND_SPACES = Pattern.compile("[\\d.,:\\s]+");

    /** A generic class needs its title: alone it would name every Proton or Java window open. */
    public WindowIdentity {
        if (windowClass == null || windowClass.isBlank()) {
            throw new IllegalArgumentException("a window identity needs a class");
        }
        windowClass = windowClass.trim();
        title = title == null || title.isBlank() ? null : title.trim();
        if (title == null && isGeneric(windowClass)) {
            throw new IllegalArgumentException(windowClass + " names no particular program: give its title too");
        }
    }

    /**
     * The identity {@code windowClass} and {@code title} name, or null when they name no one window: a generic class
     * whose title is a {@linkplain #isPlaceholder placeholder}.
     */
    public static WindowIdentity of(String windowClass, String title) {
        if (windowClass == null || windowClass.isBlank()) return null;
        if (isGeneric(windowClass) && isPlaceholder(windowClass, title)) return null;
        return new WindowIdentity(windowClass, title);
    }

    /** Whether {@code window}'s title says nothing of its own: blank, or its class (Proton's {@code steam_proton}). */
    public static boolean isPlaceholder(GenericWindow window) {
        return window == null || isPlaceholder(window.getWindowClass(), window.getTitle());
    }

    private static boolean isPlaceholder(String windowClass, String title) {
        return title == null || title.isBlank() || (windowClass != null && title.trim().equalsIgnoreCase(windowClass.trim()));
    }

    /** {@code spec} read back — {@code class} or {@code class|title} — or null when it names no one window. */
    public static WindowIdentity parse(String spec) {
        if (spec == null || spec.isBlank()) return null;
        int at = spec.indexOf(SEPARATOR);
        return of(at < 0 ? spec : spec.substring(0, at), at < 0 ? null : spec.substring(at + 1));
    }

    /**
     * What names {@code window}: its class, with its title when the class is {@linkplain #isGeneric generic}; null
     * when its class is unknown, or generic and the window untitled.
     */
    public static WindowIdentity of(GenericWindow window) {
        String windowClass = window == null ? null : window.getWindowClass();
        return of(windowClass, isGeneric(windowClass) ? window.getTitle() : null);
    }

    /** Whether {@code windowClass} is one unrelated programs share, so that it cannot name a game alone. */
    public static boolean isGeneric(String windowClass) {
        return windowClass != null && GENERIC.contains(windowClass.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether {@code window} is the one this names: the same class, ignoring case, and the same title if one is given,
     * but for its numbers and white space ({@link #sameGame}).
     */
    public boolean matches(GenericWindow window) {
        if (window == null || window.getWindowClass() == null
                || !window.getWindowClass().trim().equalsIgnoreCase(windowClass)) {
            return false;
        }
        // A title that counts frames or time still names its game: "Game - 60 fps" is "Game - 144 fps".
        return title == null || (window.getTitle() != null && titleKey(window.getTitle()).equals(titleKey(title)));
    }

    /** Whether {@code other} names the same window as this, ignoring case as {@link #matches} does. */
    public boolean sameAs(WindowIdentity other) {
        return other != null && spec().equalsIgnoreCase(other.spec());
    }

    /**
     * Whether {@code other} is this window's game, its title counting frames or time differently: the same class, and
     * titles equal but for their digits and white space, ignoring case.
     */
    public boolean sameGame(WindowIdentity other) {
        return other != null && windowClass.equalsIgnoreCase(other.windowClass)
                && titleKey(title).equals(titleKey(other.title));
    }

    /** {@code title} with its numbers and white space left out, in lower case; {@code ""} for none. */
    static String titleKey(String title) {
        return title == null ? "" : NUMBERS_AND_SPACES.matcher(title).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** {@code class}, or {@code class|title}: what {@link #parse} reads back. */
    public String spec() {
        return title == null ? windowClass : windowClass + SEPARATOR + title;
    }

    @Override
    public String toString() {
        return spec();
    }
}
