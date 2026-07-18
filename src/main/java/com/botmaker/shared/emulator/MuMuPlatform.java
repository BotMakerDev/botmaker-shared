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
 * Discovers <b>MuMu Player 12</b> instances. The install directory comes from the registry; each instance is
 * a folder under {@code <install>\vms\MuMuPlayer(-Global)-12.0-<index>} with a
 * {@code config\vm_config.json}. MuMu 12 exposes each instance's ADB on a fixed port derived from its index:
 * {@code 16384 + 32*index} (instance 0 → 16384, 1 → 16416, …). The instance name is
 * {@code vm_config.json}'s {@code playerName} when present, else {@code MuMu-<index>}.
 *
 * <p>Best-effort and Windows-first: no install / no {@code vms} dir → empty list.
 *
 * <p>Note: the {@code 16384 + 32*index} port convention and the {@code vms} layout are the documented MuMu 12
 * behaviour, but this hasn't been verified against a live install here — smoke-test on a real machine, same as
 * the other parsers.
 */
public final class MuMuPlatform implements EmulatorPlatform {

    public static final String PLATFORM_ID = "mumu";
    private static final int ADB_BASE_PORT = 16384;
    private static final int ADB_PORT_STRIDE = 32;

    // MuMuPlayer-12.0-<index> or MuMuPlayerGlobal-12.0-<index> (tolerant of the minor version + a Global tag).
    private static final Pattern INSTANCE_DIR = Pattern.compile("MuMuPlayer\\w*-[\\d.]+-(\\d+)");
    private static final Pattern PLAYER_NAME = Pattern.compile("\"playerName\"\\s*:\\s*\"([^\"]*)\"");

    @Override
    public String id() {
        return PLATFORM_ID;
    }

    @Override
    public String displayName() {
        return "MuMu Player";
    }

    @Override
    public List<EmulatorInstance> discover() {
        Path vmsDir = vmsDir();
        if (vmsDir == null || !Files.isDirectory(vmsDir)) {
            return List.of();
        }
        List<EmulatorInstance> instances = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(vmsDir)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String folder = dir.getFileName().toString();
                if (!INSTANCE_DIR.matcher(folder).matches()) {
                    continue;
                }
                parseInstance(folder, readConfig(dir)).ifPresent(instances::add);
            }
        } catch (Exception e) {
            return instances;
        }
        return instances;
    }

    /** {@code <instanceDir>\config\vm_config.json} content, or {@code ""} if it can't be read. */
    private static String readConfig(Path instanceDir) {
        Path config = instanceDir.resolve("config").resolve("vm_config.json");
        try {
            return Files.isReadable(config) ? Files.readString(config) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code <InstallDir>\vms}, or {@code null} if MuMu isn't installed / can't be found. */
    private static Path vmsDir() {
        String installDir = firstNonNull(
                WindowsRegistry.read("HKLM\\SOFTWARE\\WOW6432Node\\Netease\\MuMuPlayer-12.0", "InstallDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\Netease\\MuMuPlayer-12.0", "InstallDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\WOW6432Node\\Netease\\MuMuPlayerGlobal-12.0", "InstallDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\Netease\\MuMuPlayerGlobal-12.0", "InstallDir"));
        if (installDir == null || installDir.isBlank()) {
            return null;
        }
        return Path.of(installDir.trim(), "vms");
    }

    /**
     * Parses one instance folder (+ its {@code vm_config.json}) into an instance. Package-private + pure so
     * it's unit-testable without a MuMu install. The ADB port is derived from the folder's index; the name is
     * {@code playerName} when present in the config, else {@code MuMu-<index>}.
     */
    static Optional<EmulatorInstance> parseInstance(String folderName, String vmConfigJson) {
        Matcher m = INSTANCE_DIR.matcher(folderName);
        if (!m.matches()) {
            return Optional.empty();
        }
        int index = Integer.parseInt(m.group(1));
        int adbPort = ADB_BASE_PORT + ADB_PORT_STRIDE * index;
        String name = "MuMu-" + index;
        if (vmConfigJson != null) {
            Matcher playerName = PLAYER_NAME.matcher(vmConfigJson);
            if (playerName.find() && !playerName.group(1).isBlank()) {
                name = playerName.group(1);
            }
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
