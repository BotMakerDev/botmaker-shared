package com.botmaker.shared.emulator;

/**
 * Keeps other apps off the game while a bot runs on an emulator (the user's call, 2026-11-01): Do Not Disturb on
 * for the run, so no heads-up notification covers it, and the game's notification permission denied for good, so
 * it never asks. What is left to show ({@link SystemDialog}) the run answers. Nothing here throws: a device that
 * refuses a command is run as it is.
 */
public final class QuietRun {

    private static final String NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    private QuietRun() {}

    /** Do Not Disturb as it was before a run, restored by {@link #end}. */
    public record Was(String zenMode) {}

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
     * Denies {@code packageName} posting notifications, marked as the user's choice so Android never asks again.
     * Android 13 asks; an older one has no such permission and refuses, which changes nothing.
     */
    public static void denyNotifications(AdbDevice device, String packageName) {
        if (packageName == null || !packageName.matches("[\\w.]+")) return;
        ask(device, "pm revoke " + packageName + " " + NOTIFICATIONS);
        ask(device, "pm set-permission-flags " + packageName + " " + NOTIFICATIONS + " user-set user-fixed");
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
