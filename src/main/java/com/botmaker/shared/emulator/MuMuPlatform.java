package com.botmaker.shared.emulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers <b>MuMu Player</b> instances (MuMu 12 and the MuMu 5 that followed it, Chinese and Global builds).
 * Each instance is a folder {@code <install>\vms\MuMuPlayer(Global)-<android>-<index>} with a
 * {@code configs\vm_config.json} ({@code config\} in older builds).
 *
 * <p>The ADB port is the one {@code vm_config.json} forwards ({@code vm.nat.port_forward.adb.host_port}), else
 * MuMu's documented {@code 16384 + 32*index}. The name is what {@code MuMuManager info -v all} reports, else the
 * config's {@code playerName}, else {@code MuMu-<index>}.
 *
 * <p>Best-effort and Windows-first: no install / no {@code vms} dir → empty list.
 */
public final class MuMuPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.MUMU;
    private static final int ADB_BASE_PORT = 16384;
    private static final int ADB_PORT_STRIDE = 32;

    private static final ObjectMapper JSON = new ObjectMapper();

    // MuMuPlayer-12.0-<index> or MuMuPlayerGlobal-15.0-<index> (tolerant of the Android version + a Global tag).
    private static final Pattern INSTANCE_DIR = Pattern.compile("MuMuPlayer\\w*-[\\d.]+-(\\d+)");
    private static final Pattern PLAYER_NAME = Pattern.compile("\"playerName\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ADB_HOST_PORT =
            Pattern.compile("\"adb\"\\s*:\\s*\\{[^}]*\"host_port\"\\s*:\\s*\"?(\\d+)");

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
            Map<Integer, String> names = InstallLocator.names(console, StandardCharsets.UTF_8,
                    MuMuPlatform::parseInfo, "info", "-v", "all");
            instances.addAll(PlatformScan.directory(install.resolve("vms"), dir -> {
                if (!Files.isDirectory(dir)) {
                    return Optional.empty();
                }
                String folder = dir.getFileName().toString();
                Matcher m = INSTANCE_DIR.matcher(folder);
                if (!m.matches()) {
                    return Optional.empty();
                }
                int index = Integer.parseInt(m.group(1));
                return parseInstance(folder, readConfig(dir))
                        .map(base -> names.containsKey(index) ? base.withName(names.get(index)) : base)
                        .map(base -> withLaunch(base, index, console));
            }));
        }
        return instances;
    }

    /**
     * Attaches MuMu's {@code MuMuManager.exe control -v <i> launch/shutdown} host commands to a parsed
     * instance. Package-private + pure so it's unit-testable; returns {@code base} unchanged with no console.
     */
    static EmulatorInstance withLaunch(EmulatorInstance base, int index, Path console) {
        if (console == null) {
            return base;
        }
        String exe = console.toString();
        String idx = String.valueOf(index);
        return base.withCommands(
                List.of(exe, "control", "-v", idx, "launch"),
                List.of(exe, "control", "-v", idx, "shutdown"));
    }

    /** {@code MuMuManager.exe}: under {@code nx_main\} since MuMu 5, under {@code shell\} before; or {@code null}. */
    static Path consoleOf(Path install) {
        for (String folder : List.of("nx_main", "shell")) {
            Path console = install.resolve(folder).resolve("MuMuManager.exe");
            if (Files.isRegularFile(console)) return console;
        }
        return null;
    }

    /**
     * The instance names {@code MuMuManager info -v all} reports, by index. Its answer is a JSON object keyed by
     * index ({@code {"0": {"index": "0", "name": "Android Device", …}}}), or one such object alone when there is a
     * single instance. Empty for {@code null} or anything unreadable.
     */
    static Map<Integer, String> parseInfo(String output) {
        Map<Integer, String> names = new LinkedHashMap<>();
        if (output == null || output.isBlank()) return names;
        try {
            JsonNode root = JSON.readTree(output.substring(Math.max(0, output.indexOf('{'))));
            List<JsonNode> entries = new ArrayList<>();
            if (root.has("index")) entries.add(root);
            else root.forEach(entries::add);
            for (JsonNode entry : entries) {
                String name = entry.path("name").asText("");
                String index = entry.path("index").asText("");
                if (!name.isBlank() && index.matches("\\d+")) names.put(Integer.parseInt(index), name);
            }
        } catch (Exception e) {
            // the config names, then
        }
        return names;
    }

    /** {@code <instanceDir>\configs\vm_config.json} (or {@code config\} in older builds), or {@code ""}. */
    private static String readConfig(Path instanceDir) {
        for (String folder : List.of("configs", "config")) {
            Path config = instanceDir.resolve(folder).resolve("vm_config.json");
            try {
                if (Files.isReadable(config)) return Files.readString(config);
            } catch (Exception e) {
                // try the other folder
            }
        }
        return "";
    }

    /**
     * Every MuMu install: the {@code Netease} keys MuMu 12 wrote, then the uninstall entries every version writes
     * ({@code MuMuPlayerGlobal}, {@code MuMuPlayer-12.0}, …), then the default {@code Netease\MuMuPlayer*} folders.
     * Only a folder holding {@code vms\} counts.
     */
    private static List<Path> installDirs() {
        List<Path> candidates = new ArrayList<>();
        for (String product : List.of("MuMuPlayer-12.0", "MuMuPlayerGlobal-12.0")) {
            for (String view : List.of("HKLM\\SOFTWARE\\WOW6432Node\\Netease\\", "HKLM\\SOFTWARE\\Netease\\")) {
                candidates.add(InstallLocator.path(WindowsRegistry.read(view + product, "InstallDir")));
            }
        }
        candidates.addAll(InstallLocator.uninstallFolders(
                name -> name.toLowerCase(java.util.Locale.ROOT).startsWith("mumu")));
        candidates.addAll(InstallLocator.children(InstallLocator.programFiles("Netease")));
        return InstallLocator.existing(candidates, dir -> Files.isDirectory(dir.resolve("vms")));
    }

    /**
     * Parses one instance folder (+ its {@code vm_config.json}) into an instance. Package-private + pure so
     * it's unit-testable without a MuMu install. The ADB port is the config's forwarded one, else derived from
     * the folder's index; the name is {@code playerName} when present in the config, else {@code MuMu-<index>}.
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
            Matcher forwarded = ADB_HOST_PORT.matcher(vmConfigJson);
            if (forwarded.find()) {
                adbPort = Integer.parseInt(forwarded.group(1));
            }
        }
        return Optional.of(new EmulatorInstance(PLATFORM_ID, name, "127.0.0.1", adbPort));
    }
}
