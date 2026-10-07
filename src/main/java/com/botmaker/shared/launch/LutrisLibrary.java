package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The games Lutris has installed, as Lutris itself lists them ({@code lutris -l -o -j}).
 *
 * <p>Asked of the CLI rather than read off disk because Lutris keeps its library in an SQLite database
 * ({@code pga.db}), and this module carries no SQLite driver. The CLI answers the same rows as JSON, with the
 * cover path already resolved, in about a second — so the answer is cached for a minute, like
 * {@link HeroicLibrary}'s, for the "is it running?" question that asks it on every poll.
 *
 * <p>Here rather than in {@code game/} for the reason {@link HeroicLibrary} is: the launch stack needs the
 * game's <em>title</em> to tell whether it is running (Lutris runs a game under a {@code lutris-wrapper}
 * process named after it), and the picker needs the same rows.
 */
public final class LutrisLibrary {

    /** The Flatpak install's data, whose presence says {@code flatpak run net.lutris.Lutris} is worth trying. */
    private static final String FLATPAK_DATA = ".var/app/net.lutris.Lutris";

    /** Lutris is a Python app; a cold start takes a second or two, a stuck one is given up on. */
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(20);

    private static final long CACHE_TTL_MS = 60_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile Map<String, Game> cached;
    private static volatile long cachedAt;

    private LutrisLibrary() {}

    /**
     * One installed Lutris game.
     *
     * @param id    the library id {@code lutris:rungameid/<id>} starts, as text
     * @param name  the title Lutris shows
     * @param cover the cover file Lutris downloaded, or {@code null}
     */
    public record Game(String id, String name, Path cover) {}

    /** Every installed game, keyed by id, in Lutris's own order; empty when Lutris isn't installed. */
    public static Map<String, Game> games() {
        Map<String, Game> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return snapshot;
        }
        Map<String, Game> games = parse(listJson());
        cached = games;
        cachedAt = System.currentTimeMillis();
        return games;
    }

    /** The game with library id {@code id}, or {@code null}. */
    public static Game find(String id) {
        return id == null || id.isBlank() ? null : games().get(id.trim());
    }

    /**
     * What a running {@code id} carries: its title, which Lutris puts in its {@code lutris-wrapper} process's
     * command line and which the game's window is usually called. Empty when the id isn't installed.
     */
    public static List<String> runningTokens(String id) {
        if (id == null || id.isBlank()) return List.of();
        // A poll must not stall on a Python start-up once a minute: any list read before will do, since a game's
        // title doesn't change while it runs. Only the first ask runs lutris.
        Map<String, Game> known = cached;
        Game game = known != null && known.containsKey(id.trim()) ? known.get(id.trim()) : find(id);
        return game == null || game.name().isBlank() ? List.of() : List.of(game.name());
    }

    /** Forget the cached list — for a caller that just installed or removed a game. */
    public static void invalidate() {
        cached = null;
    }

    /** {@code lutris -l -o -j}'s output, from the native install or else the Flatpak; {@code null} when neither. */
    private static String listJson() {
        List<List<String>> ladder = new ArrayList<>();
        if (Executables.onPath("lutris")) {
            ladder.add(List.of("lutris", "-l", "-o", "-j"));
        }
        if (Executables.onPath("flatpak")
                && Files.isDirectory(Path.of(System.getProperty("user.home", ""), FLATPAK_DATA))) {
            ladder.add(List.of("flatpak", "run", LaunchKind.LUTRIS.flatpakAppId(), "-l", "-o", "-j"));
        }
        for (List<String> command : ladder) {
            try {
                Spawn.Completed listed = Spawn.run(LIST_TIMEOUT, command);
                if (listed != null && listed.ok()) return listed.output();
            } catch (Exception e) {
                Diag.log("[Lutris] " + String.join(" ", command) + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * The JSON array Lutris prints, as games. Lutris logs its own warnings on the same stream, so the array is
     * found by its first {@code [} rather than assumed to start the output. Total: anything unreadable is empty.
     */
    static Map<String, Game> parse(String output) {
        if (output == null) return Map.of();
        int start = output.indexOf('[');
        if (start < 0) return Map.of();
        Map<String, Game> games = new LinkedHashMap<>();
        try {
            JsonNode rows = MAPPER.readTree(output.substring(start));
            if (rows == null || !rows.isArray()) return Map.of();
            for (JsonNode row : rows) {
                String id = row.path("id").asText("");
                if (id.isBlank()) continue;
                String name = row.path("name").asText(row.path("slug").asText(id));
                String cover = row.path("coverPath").asText("");
                Path coverPath = cover.isBlank() ? null : Path.of(cover);
                games.putIfAbsent(id, new Game(id, name,
                        coverPath != null && Files.isRegularFile(coverPath) ? coverPath : null));
            }
        } catch (Exception e) {
            return Map.of();
        }
        return Collections.unmodifiableMap(games);
    }
}
