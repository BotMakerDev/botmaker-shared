package com.botmaker.shared.game;

import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;
import com.botmaker.shared.launch.LaunchKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Adds a Windows program to <a href="https://faugus.github.io/">Faugus Launcher</a>'s library, so it runs under
 * Proton the way every other Faugus entry does and a launch target can name it ({@code faugus:<gameid>}).
 *
 * <p>BotMaker grows no launch settings of its own (the maintainer's call): the entry gets Faugus's own defaults
 * — its default prefix folder and runner from its {@code config.json} — and the user changes anything else in
 * Faugus. The entry is appended to {@code games.json}, the file {@link FaugusLibraryScanner} reads; a Faugus
 * window already open shows it once reopened.
 */
public final class FaugusEntries {

    /** The Flatpak app id Faugus is published under on Flathub. */
    private static final String FLATPAK_ID = LaunchKind.FAUGUS.flatpakAppId();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FaugusEntries() {}

    /**
     * Whether Faugus is installed here: its binary on {@code PATH}, its Flatpak's data folder, or — for a
     * Flatpak never started yet, which has no data folder — {@code flatpak info}. Spawns that last one: off the
     * JavaFX thread when it matters.
     */
    public static boolean installed() {
        if (Executables.onPath("faugus-launcher") || Files.isDirectory(flatpakData())) return true;
        if (!Executables.onPath("flatpak")) return false;
        try {
            Spawn.Completed info = Spawn.run(Duration.ofSeconds(10), "flatpak", "info", FLATPAK_ID);
            return info != null && info.ok();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The command that installs Faugus from Flathub, or empty when {@code flatpak} isn't installed. Flatpak asks
     * for the password itself when the install is system-wide.
     */
    public static List<String> installCommand() {
        return Executables.onPath("flatpak")
                ? List.of("flatpak", "install", "-y", "--noninteractive", "flathub", FLATPAK_ID)
                : List.of();
    }

    /**
     * Adds {@code exe} as an entry named {@code title} and returns its game id, unique among the existing ones.
     *
     * @throws IOException when Faugus's library can't be read or written
     */
    public static String add(Path exe, String title) throws IOException {
        Path root = FaugusLibraryScanner.configRoot();
        if (root == null) {
            root = Executables.onPath("faugus-launcher")
                    ? Path.of(System.getProperty("user.home", ""), ".local", "share", "faugus-launcher")
                    : flatpakData().resolve("data").resolve("faugus-launcher");
        }
        Files.createDirectories(root);
        Path library = root.resolve("games.json");
        ArrayNode games = MAPPER.createArrayNode();
        if (Files.isRegularFile(library) && Files.size(library) > 0) {
            // Anything but the array Faugus writes is refused: replacing it would delete the user's library.
            if (!(MAPPER.readTree(library.toFile()) instanceof ArrayNode existing)) {
                throw new IOException(library + " isn't the list Faugus writes; it was left as it is");
            }
            games = existing;
        }
        Defaults defaults = defaults(root);
        String gameId = uniqueId(games, title);
        games.add(entry(gameId, title, exe, defaults));
        // Written beside and moved over, so a Faugus reading the file mid-write never sees half of it.
        Path partial = library.resolveSibling("games.json.botmaker");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), games);
        Files.move(partial, library, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return gameId;
    }

    /** Faugus's own default prefix folder and runner, from its {@code config.json}. */
    record Defaults(Path prefixRoot, String runner) {}

    /**
     * An entry with every key Faugus 1.4 writes, set the way its "Add game" dialog leaves them untouched. A key
     * a newer Faugus adds is filled in by Faugus with its default when it next saves.
     */
    static ObjectNode entry(String gameId, String title, Path exe, Defaults defaults) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("gameid", gameId);
        entry.put("title", title);
        entry.put("path", exe.toString());
        entry.put("prefix", defaults.prefixRoot().resolve(gameId).toString());
        for (String key : List.of("launch_arguments", "game_arguments", "mangohud", "gamemode", "sdl_enabled",
                "protonfix", "addapp_enabled", "addapp", "addapp_bat", "addapp_delay", "cover", "no_sleep",
                "icon", "steamgriddb_id", "pre_launch", "post_launch", "steam_user", "disable_umu", "runtime")) {
            entry.put(key, "");
        }
        entry.put("runner", defaults.runner());
        entry.put("addapp_first", false);
        entry.put("lossless_enabled", false);
        entry.put("lossless_multiplier", 1);
        entry.put("lossless_flow", 100);
        entry.put("lossless_performance", false);
        entry.put("lossless_hdr", false);
        entry.put("lossless_present", false);
        entry.put("playtime", 0);
        entry.put("hidden", false);
        entry.put("category", false);
        return entry;
    }

    /**
     * Faugus's own id spelling — the title lower-cased, runs of anything but letters and digits as one
     * {@code -} — made unique with a number. Package-private and pure for the tests.
     */
    static String uniqueId(JsonNode games, String title) {
        Set<String> taken = new HashSet<>();
        for (JsonNode game : games) taken.add(game.path("gameid").asText(""));
        String base = (title == null ? "" : title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "game";
        String id = base;
        for (int n = 2; taken.contains(id); n++) id = base + "-" + n;
        return id;
    }

    /** The defaults from the {@code config.json} beside {@code dataRoot}'s install; Faugus's own when unreadable. */
    private static Defaults defaults(Path dataRoot) {
        String home = System.getProperty("user.home", "");
        Path config = dataRoot.startsWith(flatpakData())
                ? flatpakData().resolve("config/faugus-launcher/config.json")
                : Path.of(home, ".config", "faugus-launcher", "config.json");
        String prefix = "";
        String runner = "";
        try {
            if (Files.isRegularFile(config)) {
                JsonNode values = MAPPER.readTree(config.toFile());
                prefix = values.path("default-prefix").asText("");
                runner = values.path("default-runner").asText("");
            }
        } catch (IOException | RuntimeException unreadable) {
            // Faugus's own defaults below
        }
        // A blank runner is left blank: Faugus applies its own default at launch, which a guess here would pin.
        return new Defaults(prefix.isBlank() ? Path.of(home, "Faugus") : Path.of(prefix), runner);
    }

    private static Path flatpakData() {
        return Path.of(System.getProperty("user.home", ""), ".var", "app", FLATPAK_ID);
    }
}
