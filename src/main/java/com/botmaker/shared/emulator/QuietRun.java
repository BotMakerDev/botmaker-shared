package com.botmaker.shared.emulator;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps other apps off the game while a bot runs on an emulator (the user's call, 2026-11-01): Do Not Disturb on
 * for the run, so no heads-up notification covers it, and every permission the game asks for granted before it
 * asks (2026-11-02). What is left to show ({@link SystemDialog}) the run answers. Nothing here throws: a device that
 * refuses a command is run as it is.
 */
public final class QuietRun {

    private QuietRun() {}

    /** Do Not Disturb as it was before a run, restored by {@link #end}. */
    public record Was(String zenMode) {}

    /**
     * Shuts off, for good, what Android pops over any app on an emulator BotMaker drives (the user's call,
     * 2026-11-03): no assistant (Gemini's overlay took taps meant for the game) and no heads-up notification.
     * Kept after the run, unlike Do Not Disturb: the emulator is the bot's. Each command is safe to repeat.
     */
    public static void calm(AdbDevice device) {
        for (String command : CALM) ask(device, command);
    }

    /** {@link #calm}'s commands, for a configuration's Apply to run as its popups-off change. */
    public static List<String> calmCommands() {
        return CALM;
    }

    /** What {@link #calmCommands} turned off, back on: the Google app and heads-up notifications. */
    public static List<String> uncalmCommands() {
        return List.of("pm enable com.google.android.googlequicksearchbox",
                "settings put global heads_up_notifications_enabled 1");
    }

    /**
     * {@link #calm}'s commands. The Google app, which shows Gemini, is disabled ({@code pm enable} brings it back): clearing
     * the assistant role alone hands it straight back to that app, its fallback holder. Then no assistant, no assist
     * gesture, no heads-up.
     */
    static final List<String> CALM = List.of(
            "pm disable-user --user 0 com.google.android.googlequicksearchbox",
            "settings put secure assist_gesture_enabled 0",
            "settings put secure assist_long_press_home_enabled 0",
            "settings put secure assist_touch_gesture_enabled 0",
            "settings delete secure assistant",
            "settings delete secure voice_interaction_service",
            "settings put global heads_up_notifications_enabled 0");

    /** Turns Do Not Disturb on and answers what it was. */
    public static Was start(AdbDevice device) {
        String was = ask(device, "settings get global zen_mode");
        ask(device, "cmd notification set_dnd on");
        return new Was(was == null ? "" : was.strip());
    }

    /** Puts Do Not Disturb back as {@code was} says. */
    public static void end(AdbDevice device, Was was) {
        ask(device, "cmd notification set_dnd " + dnd(was.zenMode()));
    }

    /**
     * Grants {@code packageName} every runtime permission it asks for and does not hold yet (the user's call,
     * 2026-11-02, in place of denying its notifications), so it never asks; a flag an earlier run fixed is cleared
     * first. A permission the device refuses to grant (an install-time one) stays as it is.
     */
    public static void grantRequested(AdbDevice device, String packageName) {
        if (packageName == null || !packageName.matches("[\\w.]+")) return;
        for (String permission : notGranted(ask(device, "dumpsys package " + packageName))) {
            ask(device, "pm clear-permission-flags " + packageName + " " + permission + " user-set user-fixed");
            ask(device, "pm grant " + packageName + " " + permission);
        }
    }

    private static final Pattern NOT_GRANTED = Pattern.compile("^\\s+([\\w.]+): granted=false", Pattern.MULTILINE);

    /** The permissions {@code dumpsys package} lists as not granted, each once, in its order. */
    static Set<String> notGranted(String dumpsys) {
        Set<String> names = new LinkedHashSet<>();
        if (dumpsys == null) return names;
        Matcher m = NOT_GRANTED.matcher(dumpsys);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /**
     * {@code set_dnd}'s word for a {@code zen_mode} setting: 0 off, 1 priority only, 2 nothing, 3 alarms; anything
     * else (unread) off.
     */
    static String dnd(String zenMode) {
        return switch (zenMode) {
            case "1" -> "priority";
            case "2" -> "none";
            case "3" -> "alarms";
            default -> "off";
        };
    }

    private static String ask(AdbDevice device, String command) {
        try {
            return device.shell(command);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
