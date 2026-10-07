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
 * Discovers <b>BlueStacks 5</b> instances, in every edition installed side by side (BlueStacks_nxt, the MSI App
 * Player's BlueStacks_msi5, …). Each edition's install/data directory comes from its registry key; the
 * per-instance ADB ports come from {@code bluestacks.conf} in its data directory, whose lines look like
 * {@code bst.instance.Rvc64.status.adb_port="5565"} (else the requested {@code bst.instance.Rvc64.adb_port}),
 * with an optional {@code bst.instance.Rvc64.display_name="..."}.
 *
 * <p>Best-effort and Windows-first: no registry key / no conf file → empty list.
 */
public final class BlueStacksPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.BLUESTACKS;
    private static final String CONF_FILE = "bluestacks.conf";

    // bst.instance.<name>.status.adb_port="<port>" — the port the engine actually took
    private static final Pattern ADB_PORT =
            Pattern.compile("^bst\\.instance\\.([^.]+)\\.status\\.adb_port=\"(\\d+)\"", Pattern.MULTILINE);
    // bst.instance.<name>.adb_port="<port>" — the one it asks for, and all an instance never started has
    private static final Pattern REQUESTED_ADB_PORT =
            Pattern.compile("^bst\\.instance\\.([^.]+)\\.adb_port=\"(\\d+)\"", Pattern.MULTILINE);
    // bst.instance.<name>.display_name="<name>"
    private static final Pattern DISPLAY_NAME =
            Pattern.compile("^bst\\.instance\\.([^.]+)\\.display_name=\"([^\"]*)\"", Pattern.MULTILINE);

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return !editions().isEmpty();
    }

    @Override
    public List<EmulatorInstance> discover() {
        List<EmulatorInstance> instances = new ArrayList<>();
        for (Edition edition : editions()) {
            if (edition.conf() == null || !Files.isReadable(edition.conf())) continue;
            try {
                List<EmulatorInstance> found = parseConf(Files.readString(edition.conf()), edition.hdPlayer());
                EmulatorState state = state(found.size(), InstallLocator.processes(edition.hdPlayer()),
                        InstallLocator.anyProcessNamed("HD-Player.exe"));
                found.forEach(instance -> instances.add(instance.withState(state)));
            } catch (Exception e) {
                // this edition's instances are lost, the others' are not
            }
        }
        return instances;
    }

    /**
     * BlueStacks' Multi-Instance Manager, beside the first edition's player: it has no console that creates an
     * instance, and the manager is where the user picks its Android version.
     */
    @Override
    public NewInstance newInstance() {
        for (Edition edition : editions()) {
            if (edition.hdPlayer() == null) continue;
            Path manager = edition.hdPlayer().resolveSibling("HD-MultiInstanceManager.exe");
            if (Files.isRegularFile(manager)) return new NewInstance(PLATFORM_ID, List.of(manager.toString()), true);
        }
        return null;
    }

    /**
     * One installed BlueStacks engine. BlueStacks ships several side by side, each under its own key and folders —
     * {@code BlueStacks_nxt} (BlueStacks 5), {@code BlueStacks_msi5} (MSI App Player), {@code BlueStacks_nxt_cn}…
     *
     * @param conf     {@code bluestacks.conf} in its data folder, or {@code null}
     * @param hdPlayer {@code HD-Player.exe} in its program folder, or {@code null}
     */
    private record Edition(Path conf, Path hdPlayer) {}

    /**
     * An edition's instances' state from how many of its {@code HD-Player.exe} processes run: each instance runs
     * one, given its token on the command line, which another process cannot read. A player running says its
     * instance has started (not that Android is up) only when the edition has a single instance. No
     * {@code HD-Player.exe} running anywhere says every instance is stopped — which matters, because MuMu also
     * answers on 5555 and would otherwise pass for BlueStacks. A player under another path than the registry's
     * (a junction, a short name) says nothing firm, and the port decides, as it does for several instances.
     *
     * @param processes the count of the edition's own player, or {@code -1} when its path is unknown
     * @param anyPlayer whether any {@code HD-Player.exe} runs, or {@code null} when the process table can't be read
     */
    static EmulatorState state(int instances, int processes, Boolean anyPlayer) {
        if (Boolean.FALSE.equals(anyPlayer)) return EmulatorState.STOPPED;
        return processes > 0 && instances == 1 ? EmulatorState.STARTING : EmulatorState.UNKNOWN;
    }

    /** Every engine key under {@code HKLM\SOFTWARE} that names a program or a data folder. */
    private static List<Edition> editions() {
        List<Edition> editions = new ArrayList<>();
        for (String key : WindowsRegistry.subkeys("HKLM\\SOFTWARE")) {
            if (!ENGINE_KEY.matcher(key).matches()) continue;
            String path = "HKLM\\SOFTWARE\\" + key;
            Path install = InstallLocator.path(WindowsRegistry.read(path, "InstallDir"));
            Path data = InstallLocator.path(WindowsRegistry.firstNonBlank(
                    WindowsRegistry.read(path, "UserDefinedDir"), WindowsRegistry.read(path, "DataDir")));
            if (install == null && data == null) continue;
            editions.add(new Edition(data == null ? null : data.resolve(CONF_FILE),
                    install == null ? null : install.resolve("HD-Player.exe")));
        }
        return editions;
    }

    /** {@code BlueStacks_nxt}, {@code BlueStacks_msi5}, …; not {@code BlueStacksServices} or {@code BlueStacks X}. */
    private static final Pattern ENGINE_KEY = Pattern.compile("(?i)BlueStacks_\\w+");

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

        Map<String, Integer> ports = new LinkedHashMap<>();
        Matcher requested = REQUESTED_ADB_PORT.matcher(conf);
        while (requested.find()) {
            ports.put(requested.group(1), Integer.parseInt(requested.group(2)));
        }
        Matcher taken = ADB_PORT.matcher(conf);
        while (taken.find()) {
            ports.put(taken.group(1), Integer.parseInt(taken.group(2)));
        }

        List<EmulatorInstance> instances = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ports.entrySet()) {
            String token = entry.getKey();
            int port = entry.getValue();
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
}
