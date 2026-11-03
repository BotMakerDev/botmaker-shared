package com.botmaker.shared.launch;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.game.FaugusLibraryScanner;
import com.botmaker.shared.game.SteamLibraryScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The installed game's version, best effort (run reports spec §4), as one line of text for a run's log: what the
 * launcher's own files say, else what the program states of itself, else {@code build <12 hex>} of its file, else
 * {@link #UNKNOWN}. Never throws, never waits longer than {@link #LIMIT}.
 *
 * <ul>
 *   <li>Android app: {@code dumpsys package}'s {@code versionName (versionCode)}, only while its instance runs.</li>
 *   <li>Steam: the manifest's {@code buildid}; a non-Steam shortcut is unknown.</li>
 *   <li>Heroic and Epic: legendary's {@code installed.json} {@code version}; GOG: {@code gog_store/installed.json}.</li>
 *   <li>Faugus, an executable: the program's PE version ({@link PeVersion}), else its file's hash.</li>
 *   <li>Lutris, Bottles, a command, a desktop entry, a link: unknown (the program is in Lutris's database, behind
 *   wine or env in a command, or nowhere).</li>
 * </ul>
 */
public final class GameVersion {

    /** What a version that could not be read is written as. */
    public static final String UNKNOWN = "unknown";

    /** The longest {@link #of} waits; a slower answer (a hash of a large file, a busy device) is unknown. */
    static final Duration LIMIT = Duration.ofSeconds(2);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BUILD_ID = Pattern.compile("\"buildid\"\\s+\"(\\d+)\"");
    private static final Pattern VERSION_NAME = Pattern.compile("\\bversionName=(\\S+)");
    private static final Pattern VERSION_CODE = Pattern.compile("\\bversionCode=(\\d+)");

    private GameVersion() {}

    /** {@code spec}'s game version, or {@link #UNKNOWN}; answers within {@link #LIMIT}. */
    public static String of(LaunchSpec spec) {
        if (spec == null) return UNKNOWN;
        CompletableFuture<String> version = new CompletableFuture<>();
        Thread reader = new Thread(() -> version.complete(read(spec)), "game-version");
        reader.setDaemon(true);
        reader.start();
        try {
            return version.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNKNOWN;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // A hash of a large file stops at its next chunk instead of reading on after the run started.
            reader.interrupt();
            return UNKNOWN;
        }
    }

    /** {@link #of} without the time limit. */
    static String read(LaunchSpec spec) {
        try {
            String version = switch (spec.kind()) {
                case EMULATOR_APP -> android(spec);
                case STEAM -> SteamIds.isShortcut(spec.token()) ? null
                        : SteamLibraryScanner.manifest(SteamLibraryScanner.root(), spec.token())
                        .map(GameVersion::readOrNull).map(GameVersion::steam).orElse(null);
                case HEROIC, EPIC -> heroic(spec.token().trim());
                case FAUGUS -> {
                    Path root = FaugusLibraryScanner.configRoot();
                    yield root == null ? null
                            : faugusPath(readOrNull(root.resolve("games.json")), spec.token().trim())
                            .map(GameVersion::ofFile).orElse(null);
                }
                case EXE -> ofFile(Path.of(spec.token().trim()));
                // cli: is not read: its first word is as often wine, env or a script as the game.
                default -> null;
            };
            return version == null || version.isBlank() ? UNKNOWN : version;
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** The program's own version, else {@code build <first 12 hex of its sha256>}; {@code null} for no file. */
    static String ofFile(Path program) {
        if (!Files.isRegularFile(program)) return null;
        Optional<String> stated = PeVersion.of(program);
        if (stated.isPresent()) return stated.get();
        try (InputStream in = Files.newInputStream(program)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[1 << 16];
            for (int n; (n = in.read(chunk)) >= 0; ) {
                if (Thread.currentThread().isInterrupted()) return null;
                sha.update(chunk, 0, n);
            }
            return "build " + HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String android(LaunchSpec spec) {
        Optional<EmulatorInstance> instance = EmulatorInstances.find(spec.emulatorInstance());
        if (instance.isEmpty() || !EmulatorReadiness.isReady(instance.get())) return null;
        String pkg = spec.emulatorPackage() == null ? "" : spec.emulatorPackage().replaceAll("[^A-Za-z0-9._]", "");
        if (pkg.isEmpty()) return null;
        try (AdbDevice device = AdbDevice.connect(instance.get().adb())) {
            return android(device.shell("dumpsys package " + pkg));
        }
    }

    /** {@code versionName (versionCode)} from {@code dumpsys package <pkg>}, the first package entry's. Pure. */
    static String android(String dumpsys) {
        if (dumpsys == null) return null;
        Matcher name = VERSION_NAME.matcher(dumpsys);
        Matcher code = VERSION_CODE.matcher(dumpsys);
        boolean hasName = name.find(), hasCode = code.find();
        if (hasName && hasCode) return name.group(1) + " (" + code.group(1) + ")";
        if (hasName) return name.group(1);
        return hasCode ? "versionCode " + code.group(1) : null;
    }

    /** {@code buildid <n>} from a Steam {@code appmanifest_<id>.acf}. Pure. */
    static String steam(String manifest) {
        Matcher m = manifest == null ? null : BUILD_ID.matcher(manifest);
        return m != null && m.find() ? "buildid " + m.group(1) : null;
    }

    private static String heroic(String appName) {
        for (Path root : HeroicLibrary.configRoots()) {
            String epic = epic(readOrNull(root.resolve("legendaryConfig/legendary/installed.json")), appName);
            if (epic != null) return epic;
            String gog = gog(readOrNull(root.resolve("gog_store/installed.json")), appName);
            if (gog != null) return gog;
        }
        String home = System.getProperty("user.home", "");
        return home.isBlank() ? null : epic(readOrNull(Path.of(home, ".config/legendary/installed.json")), appName);
    }

    /** {@code version} of {@code appName} in legendary's {@code installed.json} (an object keyed by app name). */
    static String epic(String installed, String appName) {
        JsonNode root = tree(installed);
        if (root == null) return null;
        JsonNode game = root.path(appName);
        if (game.isMissingNode()) {
            for (JsonNode entry : root) {
                if (appName.equals(entry.path("app_name").asText())) game = entry;
            }
        }
        return text(game.path("version"));
    }

    /** {@code version} of {@code appName} in Heroic's {@code gog_store/installed.json} ({@code {"installed": […]}}). */
    static String gog(String installed, String appName) {
        JsonNode root = tree(installed);
        if (root == null) return null;
        for (JsonNode entry : root.path("installed")) {
            if (appName.equals(entry.path("appName").asText())) return text(entry.path("version"));
        }
        return null;
    }

    /** The program {@code path} of Faugus's game {@code gameId} in its {@code games.json} (an array). */
    static Optional<Path> faugusPath(String games, String gameId) {
        JsonNode root = tree(games);
        if (root == null) return Optional.empty();
        for (JsonNode game : root) {
            if (gameId.equals(game.path("gameid").asText())) {
                String path = game.path("path").asText("");
                return path.isBlank() ? Optional.empty() : Optional.of(Path.of(path));
            }
        }
        return Optional.empty();
    }

    private static String text(JsonNode node) {
        String value = node.isValueNode() ? node.asText("").strip() : "";
        return value.isEmpty() ? null : value;
    }

    private static JsonNode tree(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readOrNull(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
