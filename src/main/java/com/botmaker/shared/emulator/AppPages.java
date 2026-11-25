// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pages an Android app can start on (spec {@code 2026-11-25-game-window-design.md}, Starts on): the doors that
 * land in it and the activities it opens to an intent, read from {@code dumpsys package}; and the page open on a
 * device now, read from its focus, when a door or one of those activities reaches it. A page nothing reaches is no
 * start: walking there is the bot's work.
 */
public final class AppPages {

    private AppPages() {}

    /** The page open on a device now, as a launch target could start on it. */
    public sealed interface Now {}

    /** A door or an activity the app opens to an intent reaches the page in focus. */
    public record Reached(AndroidPage page) implements Now {}

    /** The page in focus, {@code name} its activity's short component, which no intent opens directly. */
    public record Unreachable(String name) implements Now {}

    /** No app's page has the focus (a lock screen, the status bar), or the device did not answer. */
    public record NoPage() implements Now {}

    /** The pages {@code packageName} starts on, on {@code device}: its doors first, then its activities. */
    public static List<AndroidPage> pages(AdbDevice device, String packageName) {
        List<AndroidPage> pages = new ArrayList<>();
        AndroidDoor.into(packageName).forEach(door -> pages.add(new AndroidPage.Door(door)));
        pages.addAll(exported(device, packageName));
        return List.copyOf(pages);
    }

    /** The activities of {@code packageName} an intent opens, on {@code device}; empty when it doesn't answer. */
    public static List<AndroidPage.Activity> exported(AdbDevice device, String packageName) {
        try {
            return exported(packageName, device.shell("dumpsys package " + packageName));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** What {@code device} has in focus now, as a page a launch target could start on. */
    public static Now openNow(AdbDevice device) {
        String focus;
        try {
            focus = device.shell(AdbDevice.FOCUS);
        } catch (RuntimeException e) {
            return new NoPage();
        }
        return focused(focus).map(component -> {
            String pkg = component.substring(0, component.indexOf('/'));
            return openNow(component, exported(device, pkg));
        }).orElseGet(NoPage::new);
    }

    /**
     * The activities of {@code packageName} in {@code dumpsys}'s Activity Resolver Table, the ones with an intent
     * filter, each once, as written; the receiver and service tables after it are not pages.
     */
    static List<AndroidPage.Activity> exported(String packageName, String dumpsys) {
        if (dumpsys == null || packageName == null) return List.of();
        Set<String> found = new LinkedHashSet<>();
        boolean inActivities = false;
        for (String line : dumpsys.split("\\R")) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                inActivities = line.startsWith("Activity Resolver Table:");
                continue;
            }
            if (!inActivities) continue;
            Matcher m = RESOLVED.matcher(line);
            if (m.find() && m.group(1).equals(packageName)) found.add(m.group(1) + "/" + m.group(2));
        }
        return found.stream().map(AndroidPage.Activity::new).toList();
    }

    /** {@code component} (focused, {@code pkg/cls}) as a page: a door landing on it first, then an exported one. */
    static Now openNow(String component, List<AndroidPage.Activity> exported) {
        String pkg = component.substring(0, component.indexOf('/'));
        String cls = full(pkg, component.substring(component.indexOf('/') + 1));
        for (AndroidDoor door : AndroidDoor.into(pkg)) {
            if (door.landsOn(pkg, cls)) return new Reached(new AndroidPage.Door(door));
        }
        for (AndroidPage.Activity activity : exported) {
            String id = activity.component();
            if (id.startsWith(pkg + "/") && full(pkg, id.substring(id.indexOf('/') + 1)).equals(cls)) {
                return new Reached(activity);
            }
        }
        return new Unreachable(pkg + "/" + shortName(pkg, cls));
    }

    /**
     * The focused activity's component in {@link AdbDevice#FOCUS}'s lines: the focus window's when it is an
     * activity's, else the focused app's (a popup over the page holds the focus window).
     */
    static Optional<String> focused(String focus) {
        if (focus == null) return Optional.empty();
        Matcher window = FOCUS_WINDOW.matcher(focus);
        if (window.find()) return Optional.of(window.group(1) + "/" + window.group(2));
        Matcher app = FOCUSED_APP.matcher(focus);
        return app.find() ? Optional.of(app.group(1) + "/" + app.group(2)) : Optional.empty();
    }

    private static String full(String pkg, String cls) {
        return cls.startsWith(".") ? pkg + cls : cls;
    }

    private static String shortName(String pkg, String cls) {
        return cls.startsWith(pkg + ".") ? cls.substring(pkg.length()) : cls;
    }

    // `        4b1a2c3 com.android.settings/.Settings$WifiSettingsActivity filter 7d8e9f0`
    private static final Pattern RESOLVED = Pattern.compile("^\\s+\\p{XDigit}+ ([\\w.]+)/([\\w.$]+) filter ");
    // `mCurrentFocus=Window{1a2b u0 com.foo/com.foo.Main}`
    private static final Pattern FOCUS_WINDOW = Pattern.compile("mCurrentFocus=Window\\{\\S+ \\S+ ([\\w.]+)/([\\w.$]+)\\}");
    // `mFocusedApp=ActivityRecord{3c4d u0 com.foo/.Main t12}`
    private static final Pattern FOCUSED_APP =
            Pattern.compile("mFocusedApp=ActivityRecord\\{\\S+ \\S+ ([\\w.]+)/([\\w.$]+)[ }]");
}
