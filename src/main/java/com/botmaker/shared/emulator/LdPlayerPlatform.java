package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers <b>LDPlayer</b> instances, every installed version (LDPlayer 9 and 14 install side by side). The
 * install directories come from {@link #installDirs()}; each instance is a {@code vms\config\leidian<index>.config}
 * JSON file. Its name is the title {@code ldconsole list2} reports, else the config's
 * {@code statusSettings.playerName}, else {@code leidian<index>}.
 *
 * <p>LDPlayer exposes each instance's ADB on a fixed port derived from its index: {@code 5555 + 2*index}
 * (instance 0 → 5555, 1 → 5557, …). Best-effort and Windows-first: no install / no config dir → empty list.
 */
public final class LdPlayerPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.LDPLAYER;
    private static final int ADB_BASE_PORT = 5555;

    private static final Pattern CONFIG_INDEX = Pattern.compile("leidian(\\d+)\\.config");
    // Reads statusSettings.playerName without a JSON parser: matches both the flat key
    // ("statusSettings.playerName":"X") and the nested form ("playerName":"X"). Keeps shared dep-free.
    private static final Pattern PLAYER_NAME = Pattern.compile("playerName\"\\s*:\\s*\"([^\"]*)\"");

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return !installDirs().isEmpty();
    }

    @Override
    public List<EmulatorInstance> discover() {
        List<EmulatorInstance> instances = new ArrayList<>();
        for (Path install : installDirs()) {
            Path console = consoleOf(install);
            Map<Integer, InstallLocator.ConsoleRow> rows = InstallLocator.list(console,
                    InstallLocator.SYSTEM_CODE_PAGE, LdPlayerPlatform::parseList2, "list2");
            instances.addAll(PlatformScan.directory(install.resolve("vms").resolve("config"), file -> {
                String fileName = file.getFileName().toString();
                Matcher m = CONFIG_INDEX.matcher(fileName);
                if (!m.matches()) {
                    return Optional.empty(); // skip leidian.config (global) and non-instance files
                }
                int index = Integer.parseInt(m.group(1));
                return parseInstance(fileName, Files.readString(file))
                        .map(base -> rows.containsKey(index)
                                ? base.withName(rows.get(index).name()).withState(rows.get(index).state()) : base)
                        .map(base -> withLaunch(base, index, console));
            }));
        }
        return instances;
    }

    /**
     * {@code ldconsole list2}, one instance per line: {@code index,title,topHwnd,bindHwnd,android,pid,vboxPid,
     * width,height,dpi}. {@code android} is 1 once Android is up (2 while it boots, in LDPlayer 14); a positive
     * {@code pid} before that means the player is starting.
     */
    static Map<Integer, InstallLocator.ConsoleRow> parseList2(String output) {
        return InstallLocator.csvRows(output, fields -> {
            Long android = InstallLocator.field(fields, 4);
            Long pid = InstallLocator.field(fields, 5);
            if (android == null) return EmulatorState.UNKNOWN;
            if (android == 1) return EmulatorState.RUNNING;
            return pid != null && pid > 0 ? EmulatorState.STARTING : EmulatorState.STOPPED;
        });
    }

    /**
     * Every LDPlayer install, each version once: the {@code leidian} keys LDPlayer 9 writes, the uninstall entry
     * every version writes ({@code LDPlayer14}, its folder taken from the icon path because it leaves
     * {@code InstallLocation} empty), then the default {@code C:\LDPlayer\LDPlayer<n>}. Only a folder holding
     * instance configs counts.
     */
    private static List<Path> installDirs() {
        List<Path> candidates = new ArrayList<>();
        for (String key : List.of("HKLM\\SOFTWARE\\leidian\\LDPlayer9", "HKLM\\SOFTWARE\\WOW6432Node\\leidian\\LDPlayer9",
                "HKLM\\SOFTWARE\\leidian\\LDPlayer")) {
            candidates.add(InstallLocator.path(WindowsRegistry.read(key, "InstallDir")));
        }
        candidates.addAll(InstallLocator.uninstallFolders(name -> UNINSTALL_NAME.matcher(name).find()));
        String drive = System.getenv("SystemDrive");
        if (drive != null && !drive.isBlank()) {
            candidates.addAll(InstallLocator.children(Path.of(drive + "\\", "LDPlayer")));
        }
        return InstallLocator.existing(candidates, dir -> Files.isDirectory(dir.resolve("vms").resolve("config")));
    }

    private static final Pattern UNINSTALL_NAME = Pattern.compile("(?i)^(LDPlayer|leidian)");

    /** {@code ldconsole.exe}, or the older {@code dnconsole.exe} it replaced; {@code null} when neither is there. */
    static Path consoleOf(Path install) {
        for (String name : List.of("ldconsole.exe", "dnconsole.exe")) {
            Path console = install.resolve(name);
            if (Files.isRegularFile(console)) return console;
        }
        return null;
    }

    /**
     * Attaches LDPlayer's {@code ldconsole.exe launch/quit --index <i>} host commands to a parsed instance.
     * Package-private + pure so it's unit-testable; returns {@code base} unchanged when the console is absent.
     */
    static EmulatorInstance withLaunch(EmulatorInstance base, int index, Path console) {
        if (console == null) {
            return base;
        }
        String exe = console.toString();
        String idx = String.valueOf(index);
        return base.withCommands(
                List.of(exe, "launch", "--index", idx),
                List.of(exe, "quit", "--index", idx));
    }

    /**
     * Parses one {@code leidian<index>.config} into an instance. Package-private + pure so it's unit-testable
     * without an LDPlayer install. The ADB port is derived from the file's index; the name is
     * {@code statusSettings.playerName} when present, else {@code leidian<index>}.
     */
    static Optional<EmulatorInstance> parseInstance(String fileName, String json) {
        Matcher m = CONFIG_INDEX.matcher(fileName);
        if (!m.matches()) {
            return Optional.empty();
        }
        int index = Integer.parseInt(m.group(1));
        int adbPort = ADB_BASE_PORT + 2 * index;
        String name = "leidian" + index;
        Matcher playerName = PLAYER_NAME.matcher(json);
        if (playerName.find() && !playerName.group(1).isBlank()) {
            name = playerName.group(1);
        }
        return Optional.of(new EmulatorInstance(PLATFORM_ID, name, "127.0.0.1", adbPort));
    }
}
