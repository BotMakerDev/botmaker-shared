package com.botmaker.shared.emulator.config;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.botmaker.shared.emulator.config.ConfigField.LOCALE;
import static com.botmaker.shared.emulator.config.ConfigField.ROOT;
import static com.botmaker.shared.emulator.config.ConfigField.SCREEN;
import static com.botmaker.shared.emulator.config.ConfigField.TIME_ZONE;

/**
 * What a running instance's Android said, all at one time: kept as the last answer while the instance is stopped.
 * Read-only commands only, and nothing is pressed. Outputs as captured 2026-11-12 on GameLoop (Android 13), MuMu 12
 * (Android 15) and MEmu 9 (Android 9).
 *
 * @param fields          the screen apps see ({@code wm size}'s override when set), locale, time zone, and root when
 *                        {@code su} is on the path
 * @param googleAccounts  the Google emails signed in, lower case, or empty when Android did not answer; shown to the
 *                        user, never to an assistant
 * @param packages        the third-party packages installed, or empty when Android did not answer
 * @param android         {@code ro.build.version.release}, or empty
 * @param doNotDisturbNow whether Do Not Disturb is on now ({@code zen_mode} not 0)
 */
public record AndroidState(Map<ConfigField, ConfigValue> fields, Optional<Set<String>> googleAccounts,
                           Optional<Set<String>> packages, String android, boolean doNotDisturbNow, Instant at) {

    public AndroidState {
        Map<ConfigField, ConfigValue> copy = new EnumMap<>(ConfigField.class);
        copy.putAll(fields);
        fields = Collections.unmodifiableMap(copy);
        googleAccounts = frozen(googleAccounts);
        packages = frozen(packages);
        android = android == null ? "" : android.strip();
    }

    private static Optional<Set<String>> frozen(Optional<Set<String>> set) {
        return set == null ? Optional.empty()
                : set.map(s -> Collections.unmodifiableSet(new LinkedHashSet<>(s)));
    }

    /** One shell command's output, or null when it gave none. */
    @FunctionalInterface
    public interface Shell {
        String run(String command);
    }

    /** The command whose answer holds emails: its step says only how many it found. */
    public static final String ACCOUNTS = "dumpsys account";

    /** Reads every fact over {@code shell}; a command that fails leaves its fact unread. */
    public static AndroidState read(Shell shell, Instant at) {
        Map<ConfigField, ConfigValue> fields = new EnumMap<>(ConfigField.class);
        ConfigValue.Screen screen = screen(ask(shell, "wm size"), ask(shell, "wm density"));
        if (screen != null) fields.put(SCREEN, screen);
        String locale = locale(ask(shell, "getprop persist.sys.locale"), ask(shell, "getprop ro.product.locale"));
        if (locale != null) fields.put(LOCALE, new ConfigValue.Text(locale));
        String zone = line(ask(shell, "getprop persist.sys.timezone"));
        if (zone != null) fields.put(TIME_ZONE, new ConfigValue.Text(zone));
        String su = ask(shell, "which su");
        if (su != null && line(su) != null && line(su).startsWith("/")) fields.put(ROOT, new ConfigValue.Toggle(true));
        return new AndroidState(fields, Optional.ofNullable(googleAccounts(ask(shell, ACCOUNTS))),
                Optional.ofNullable(packages(ask(shell, "pm list packages -3"))),
                line(ask(shell, "getprop ro.build.version.release")), zenOn(ask(shell, "settings get global zen_mode")), at);
    }

    private static String ask(Shell shell, String command) {
        try {
            return shell.run(command);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final Pattern WM_SIZE = Pattern.compile("(Physical|Override) size: (\\d+)x(\\d+)");
    private static final Pattern WM_DENSITY = Pattern.compile("(Physical|Override) density: (\\d+)");

    /** The size and density apps see: each one's override when set, else the physical; null when either is missing. */
    static ConfigValue.Screen screen(String wmSize, String wmDensity) {
        int[] size = null;
        if (wmSize != null) {
            Matcher m = WM_SIZE.matcher(wmSize);
            while (m.find()) {
                size = new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
                if (m.group(1).equals("Override")) break;
            }
        }
        int dpi = -1;
        if (wmDensity != null) {
            Matcher m = WM_DENSITY.matcher(wmDensity);
            while (m.find()) {
                dpi = Integer.parseInt(m.group(2));
                if (m.group(1).equals("Override")) break;
            }
        }
        return size == null || dpi <= 0 || size[0] <= 0 || size[1] <= 0 ? null
                : new ConfigValue.Screen(size[0], size[1], dpi);
    }

    /** The user's locale, else the image's; null when neither says. */
    static String locale(String persist, String product) {
        String own = line(persist);
        return own != null ? own : line(product);
    }

    private static final Pattern ACCOUNT = Pattern.compile("Account \\{name=([^,]+), type=com\\.google}");

    /**
     * The Google accounts of {@code dumpsys account}'s first {@code Accounts: N} block. The lines after it (history,
     * visibility) name accounts again, some long gone, so the block ends at its first blank line. Null when there is
     * no such block: not an answer.
     */
    static Set<String> googleAccounts(String dumpsys) {
        if (dumpsys == null) return null;
        Set<String> out = new LinkedHashSet<>();
        boolean inside = false;
        for (String line : dumpsys.split("\\R")) {
            if (!inside) {
                inside = line.strip().startsWith("Accounts:");
                continue;
            }
            if (line.isBlank()) break;
            Matcher m = ACCOUNT.matcher(line);
            if (m.find()) out.add(m.group(1).strip().toLowerCase(Locale.ROOT));
        }
        return inside ? out : null;
    }

    /**
     * {@code pm list packages}' {@code package:<name>} lines; null when it lists none. A blank answer is unread too: a
     * booting package service answers so, and a game wrongly missing would be installed again.
     */
    static Set<String> packages(String pmList) {
        if (pmList == null || !pmList.contains("package:")) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String line : pmList.split("\\R")) {
            String kept = line.strip();
            if (kept.startsWith("package:") && kept.length() > "package:".length()) {
                out.add(kept.substring("package:".length()));
            }
        }
        return out;
    }

    /** {@code zen_mode}: 0 off; 1 priority, 2 nothing, 3 alarms are all on. */
    static boolean zenOn(String zenMode) {
        String value = line(zenMode);
        return value != null && value.matches("[1-9]\\d*");
    }

    /** The first line that says something, or null. */
    private static String line(String text) {
        if (text == null) return null;
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) return line.strip();
        }
        return null;
    }
}
