// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import java.util.List;
import java.util.Optional;

/**
 * The page an Android app starts on, when not its first screen: an {@link AndroidDoor} or an activity the app
 * exports ({@code com.android.settings/.wifi.WifiSettings}). Written after {@code #} in an {@code emu-app:} spec;
 * nothing else is a page, so no raw intent reaches a launch.
 */
public sealed interface AndroidPage {

    /** What a launch spec writes after {@code #}. */
    String id();

    /** {@code am start}'s arguments that open this page. */
    List<String> intentArgs();

    /** The shell command that opens it and waits for it to show. */
    default String startCommand() {
        return "am start -W " + String.join(" ", intentArgs());
    }

    /** Whether {@code am start}'s answer says it opened nothing. */
    static boolean refused(String amAnswer) {
        return amAnswer == null || amAnswer.contains("Error") || amAnswer.contains("Exception");
    }

    /** A door by its name. */
    record Door(AndroidDoor door) implements AndroidPage {
        @Override
        public String id() {
            return door.id();
        }

        @Override
        public List<String> intentArgs() {
            return door.intentArgs();
        }
    }

    /** An exported activity, {@code <package>/<class>}. */
    record Activity(String component) implements AndroidPage {
        @Override
        public String id() {
            return component;
        }

        @Override
        public List<String> intentArgs() {
            return List.of("-n", component);
        }
    }

    /**
     * The page {@code id} names: a component when it holds a {@code /}, else a known door; empty for a blank id or a
     * door this build doesn't know.
     */
    static Optional<AndroidPage> parse(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String page = id.strip();
        if (page.indexOf('/') > 0) return Optional.of(new Activity(page));
        AndroidDoor door = AndroidDoor.fromId(page);
        return door == AndroidDoor.UNKNOWN ? Optional.empty() : Optional.of(new Door(door));
    }
}
