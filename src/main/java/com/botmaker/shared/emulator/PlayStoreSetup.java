package com.botmaker.shared.emulator;

import java.util.List;

/**
 * How an emulator without Google Play gets it, said as steps the user takes: BotMaker types no password and changes
 * no system image itself. Waydroid is re-initialised with its Google Play image and the device registered with Google
 * (an uncertified device's Play refuses to sign in until it is); an Android Studio emulator is a new device made from a
 * Google Play system image; the Windows emulators ship Play already. Pure.
 *
 * @param summary one sentence: what it takes
 * @param steps   in order; empty when there is no way from here
 */
public record PlayStoreSetup(String summary, List<Step> steps) {

    /** Google's page that registers an uncertified device by its Android ID. */
    public static final String REGISTER = "https://www.google.com/android/uncertified";

    /** Google services' database inside Android, where the ID {@link #REGISTER} asks for is kept. */
    public static final String GSERVICES_DB = "/data/data/com.google.android.gsf/databases/gservices.db";

    /** Reads that ID from {@link #GSERVICES_DB}. */
    public static final String ANDROID_ID_QUERY = "select value from main where name = 'android_id';";

    /**
     * One step.
     *
     * @param text    what to do, a sentence
     * @param command a command line to type in a terminal, or {@code null}
     * @param link    a page to open, or {@code null}
     */
    public record Step(String text, String command, String link) {}

    /** The steps for {@code platform}, the instance being named {@code name}. */
    public static PlayStoreSetup of(PlatformId platform, String name) {
        return switch (platform) {
            case WAYDROID -> new PlayStoreSetup("Waydroid gets Google Play by re-initialising it with its Google Play "
                    + "image, then registering it with Google.", List.of(
                    new Step("Re-initialise Waydroid with the Google Play image. This replaces its system image: back "
                            + "up anything you keep inside it first. It asks for your password.",
                            "sudo waydroid init -s GAPPS -f", null),
                    // No "waydroid session start": it needs a Wayland desktop, and BotMaker starts Waydroid nested
                    // in its own display on an X11 one, where that command fails (KDE on X11, 2026-11-01).
                    new Step("Stop Waydroid's session, then start Waydroid again: ▶ Launch game in BotMaker starts "
                            + "it.", "waydroid session stop", null),
                    new Step("Read its Android ID (a long number).", "sudo waydroid shell -- sqlite3 "
                            + GSERVICES_DB + " \"" + ANDROID_ID_QUERY + "\"", null),
                    new Step("Register that ID with Google, signed in to your Google account, then wait a few minutes "
                            + "and stop and start Waydroid again as above.", null, REGISTER),
                    new Step("Open Google Play in Waydroid and sign in.", null, null)));
            case AVD -> new PlayStoreSetup("An Android Studio emulator has Google Play only when it was made from a "
                    + "Google Play system image.", List.of(
                    new Step("In Android Studio, open Device Manager and create a device whose system image says "
                            + "\"Google Play\" (the Play Store icon in the device list).", null, null),
                    new Step("Start it, sign in to Google Play, and pick it as the game's emulator.", null, null)));
            case BLUESTACKS, LDPLAYER, MEMU, MUMU -> new PlayStoreSetup(platform.displayName()
                    + " comes with Google Play.", List.of(
                    new Step("Open Google Play in " + name + " and sign in.", null, null)));
            case PHYSICAL -> new PlayStoreSetup("A phone without Google Play can't get it from BotMaker: install the "
                    + "game from a file instead.", List.of());
            default -> new PlayStoreSetup("BotMaker knows no way to add Google Play to " + name + ": install the game "
                    + "from a file instead.", List.of());
        };
    }

    /** The whole guide as plain text, one numbered step a line, commands and pages on their own lines. */
    public String text() {
        StringBuilder out = new StringBuilder(summary);
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            out.append('\n').append(i + 1).append(". ").append(step.text());
            if (step.command() != null) out.append("\n   ").append(step.command());
            if (step.link() != null) out.append("\n   ").append(step.link());
        }
        return out.toString();
    }
}
