package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers <b>BlueStacks 5</b> (BlueStacks_nxt) instances. The install/data directory comes from the
 * registry; the per-instance ADB ports come from {@code bluestacks.conf} in the user-data directory, whose
 * lines look like {@code bst.instance.Rvc64.status.adb_port="5565"} (and an optional
 * {@code bst.instance.Rvc64.display_name="..."}).
 *
 * <p>Best-effort and Windows-first: no registry key / no conf file → empty list.
 */
public final class BlueStacksPlatform implements EmulatorPlatform {

    public static final String PLATFORM_ID = "bluestacks";
    private static final String CONF_FILE = "bluestacks.conf";

    // bst.instance.<name>.status.adb_port="<port>"
    private static final Pattern ADB_PORT =
            Pattern.compile("^bst\\.instance\\.([^.]+)\\.status\\.adb_port=\"(\\d+)\"", Pattern.MULTILINE);
    // bst.instance.<name>.display_name="<name>"
    private static final Pattern DISPLAY_NAME =
            Pattern.compile("^bst\\.instance\\.([^.]+)\\.display_name=\"([^\"]*)\"", Pattern.MULTILINE);

    @Override
    public String id() {
        return PLATFORM_ID;
    }

    @Override
    public String displayName() {
        return "BlueStacks";
    }

    @Override
    public boolean isInstalled() {
        return confPath() != null || hdPlayerPath() != null;
    }

    @Override
    public List<EmulatorInstance> discover() {
        Path conf = confPath();
        if (conf == null || !Files.isReadable(conf)) {
            return List.of();
        }
        try {
            return parseConf(Files.readString(conf), hdPlayerPath());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** {@code <InstallDir>\HD-Player.exe} (the program dir, not the data dir), or {@code null} if unknown. */
    private static Path hdPlayerPath() {
        String installDir = firstNonNull(
                WindowsRegistry.read("HKLM\\SOFTWARE\\BlueStacks_nxt", "InstallDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\BlueStacks_msi", "InstallDir"));
        if (installDir == null || installDir.isBlank()) {
            return null;
        }
        return Path.of(installDir.trim(), "HD-Player.exe");
    }

    /** Locates {@code bluestacks.conf}, or {@code null} if BlueStacks isn't installed / can't be found. */
    private static Path confPath() {
        String userDefinedDir = firstNonNull(
                WindowsRegistry.read("HKLM\\SOFTWARE\\BlueStacks_nxt", "UserDefinedDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\BlueStacks_msi", "UserDefinedDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\BlueStacks_nxt", "DataDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\BlueStacks_msi", "DataDir"));
        if (userDefinedDir == null || userDefinedDir.isBlank()) {
            return null;
        }
        return Path.of(userDefinedDir.trim(), CONF_FILE);
    }

    /**
     * Parses a {@code bluestacks.conf} body into instances. Package-private + pure so it's unit-testable
     * without a BlueStacks install. Instances are keyed by their config token; {@code display_name} (when
     * present) becomes the user-facing name, otherwise the token is used.
     */
    static List<EmulatorInstance> parseConf(String conf) {
        return parseConf(conf, null);
    }

    /**
     * As {@link #parseConf(String)}, but also attaches {@code HD-Player.exe --instance <token>} launch commands
     * when {@code hdPlayer} is known. BlueStacks has no documented clean-stop CLI, so the stop command is left
     * empty (the instance is stopped by closing its window / killing the process). The launch selector is the
     * config <em>token</em> (e.g. {@code Rvc64}), not the display name.
     */
    static List<EmulatorInstance> parseConf(String conf, Path hdPlayer) {
        Map<String, String> names = new LinkedHashMap<>();
        Matcher nameMatcher = DISPLAY_NAME.matcher(conf);
        while (nameMatcher.find()) {
            String display = nameMatcher.group(2);
            names.put(nameMatcher.group(1), (display == null || display.isBlank()) ? nameMatcher.group(1) : display);
        }

        List<EmulatorInstance> instances = new ArrayList<>();
        Matcher portMatcher = ADB_PORT.matcher(conf);
        while (portMatcher.find()) {
            String token = portMatcher.group(1);
            int port = Integer.parseInt(portMatcher.group(2));
            String name = names.getOrDefault(token, token);
            EmulatorInstance instance = new EmulatorInstance(PLATFORM_ID, name, "127.0.0.1", port);
            if (hdPlayer != null) {
                instance = instance.withCommands(
                        List.of(hdPlayer.toString(), "--instance", token),
                        List.of());
            }
            instances.add(instance);
        }
        return instances;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
