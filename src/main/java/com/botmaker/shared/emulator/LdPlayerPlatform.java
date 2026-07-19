package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers <b>LDPlayer</b> (LDPlayer9) instances. The install directory comes from the registry; each
 * instance is a {@code vms\config\leidian<index>.config} JSON file whose {@code statusSettings.playerName}
 * (falling back to {@code leidian<index>}) is the instance name.
 *
 * <p>LDPlayer exposes each instance's ADB on a fixed port derived from its index: {@code 5555 + 2*index}
 * (instance 0 → 5555, 1 → 5557, …). Best-effort and Windows-first: no install / no config dir → empty list.
 */
public final class LdPlayerPlatform implements EmulatorPlatform {

    public static final String PLATFORM_ID = "ldplayer";
    private static final int ADB_BASE_PORT = 5555;

    private static final Pattern CONFIG_INDEX = Pattern.compile("leidian(\\d+)\\.config");
    // Reads statusSettings.playerName without a JSON parser: matches both the flat key
    // ("statusSettings.playerName":"X") and the nested form ("playerName":"X"). Keeps shared dep-free.
    private static final Pattern PLAYER_NAME = Pattern.compile("playerName\"\\s*:\\s*\"([^\"]*)\"");

    @Override
    public String id() {
        return PLATFORM_ID;
    }

    @Override
    public String displayName() {
        return "LDPlayer";
    }

    @Override
    public List<EmulatorInstance> discover() {
        Path install = installDir();
        Path configDir = (install == null) ? null : install.resolve("vms").resolve("config");
        if (configDir == null || !Files.isDirectory(configDir)) {
            return List.of();
        }
        Path console = install.resolve("ldconsole.exe");
        List<EmulatorInstance> instances = new ArrayList<>();
        try (Stream<Path> files = Files.list(configDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                String fileName = file.getFileName().toString();
                Matcher m = CONFIG_INDEX.matcher(fileName);
                if (!m.matches()) {
                    continue; // skip leidian.config (global) and non-instance files
                }
                int index = Integer.parseInt(m.group(1));
                try {
                    parseInstance(fileName, Files.readString(file))
                            .map(base -> withLaunch(base, index, console))
                            .ifPresent(instances::add);
                } catch (Exception ignored) {
                    // skip an unreadable/instance config; keep discovering the rest
                }
            }
        } catch (Exception e) {
            return instances;
        }
        return instances;
    }

    /** {@code <InstallDir>}, or {@code null} if LDPlayer isn't installed / can't be found. */
    private static Path installDir() {
        String installDir = firstNonNull(
                WindowsRegistry.read("HKLM\\SOFTWARE\\leidian\\LDPlayer9", "InstallDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\WOW6432Node\\leidian\\LDPlayer9", "InstallDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\leidian\\LDPlayer", "InstallDir"));
        if (installDir == null || installDir.isBlank()) {
            return null;
        }
        return Path.of(installDir.trim());
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

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
