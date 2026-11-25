// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A page Android opens by a public intent, by a name people read: the doors a launch target may start an app on
 * (2026-11-25, spec {@code 2026-11-25-game-window-design.md}). A page no door and no exported activity reaches is
 * not a start: the bot walks there.
 *
 * <p>Each door says the package it lands in, so a target names that package and a run knows the page is up when the
 * package has the focus.
 */
public enum AndroidDoor {
    /** Google's sign-in page, the email field first; it adds no account by itself. */
    GOOGLE_SIGN_IN("google-sign-in", "Google's sign-in page", "android.settings.ADD_ACCOUNT_SETTINGS",
            List.of("--esa", "account_types", "com.google"), "com.google.android.gms", List.of("MinuteMaidActivity")),
    /** Settings' Wi-Fi page. */
    WIFI("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS", List.of(), "com.android.settings",
            List.of("WifiSettingsActivity", "WifiSettings2Activity", "WifiPickerActivity")),
    /** Settings' list of the device's accounts. */
    ACCOUNTS("accounts", "Accounts", "android.settings.SYNC_SETTINGS", List.of(), "com.android.settings",
            List.of("AccountDashboardActivity", "AccountSyncSettingsActivity")),
    /** Settings' date and time page. */
    DATE("date", "Date & time", "android.settings.DATE_SETTINGS", List.of(), "com.android.settings",
            List.of("DateTimeSettingsActivity")),
    /** A door this build doesn't know, read from a newer bot. */
    UNKNOWN("", "Unknown page", "", List.of(), "", List.of());

    private final String id;
    private final String displayName;
    private final String action;
    private final List<String> extras;
    private final String landsIn;
    private final List<String> landings;

    AndroidDoor(String id, String displayName, String action, List<String> extras, String landsIn,
            List<String> landings) {
        this.id = id;
        this.displayName = displayName;
        this.action = action;
        this.extras = extras;
        this.landsIn = landsIn;
        this.landings = landings;
    }

    /**
     * Whether an activity of {@code packageName} named {@code activityClass} (full or short, {@code .X} or
     * {@code a.b.X}) is the page this door opens: the class's own name, after its last {@code .} or {@code $}, is one
     * the door is known to land on.
     */
    public boolean landsOn(String packageName, String activityClass) {
        if (this == UNKNOWN || packageName == null || activityClass == null || !landsIn.equals(packageName)) {
            return false;
        }
        String simple = activityClass.substring(Math.max(activityClass.lastIndexOf('.'),
                activityClass.lastIndexOf('$')) + 1);
        return landings.contains(simple);
    }

    /** The id a launch spec writes after {@code #}. */
    public String id() {
        return id;
    }

    /** The name the Game window shows. */
    public String displayName() {
        return displayName;
    }

    /** The package whose page this door opens. */
    public String landsIn() {
        return landsIn;
    }

    /** {@code am start}'s arguments for this door: the action, then its extras. */
    public List<String> intentArgs() {
        List<String> args = new ArrayList<>(List.of("-a", action));
        args.addAll(extras);
        return List.copyOf(args);
    }

    /** The doors that land in {@code packageName}, in this enum's order. */
    public static List<AndroidDoor> into(String packageName) {
        return Arrays.stream(values()).filter(door -> door != UNKNOWN && door.landsIn.equals(packageName)).toList();
    }

    /** The door {@code id} names; {@link #UNKNOWN} for any other, never a throw. */
    public static AndroidDoor fromId(String id) {
        if (id == null || id.isBlank()) return UNKNOWN;
        return Arrays.stream(values()).filter(door -> door != UNKNOWN && door.id.equals(id.strip()))
                .findFirst().orElse(UNKNOWN);
    }
}
