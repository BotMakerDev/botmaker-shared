// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.launch;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.game.FaugusLibraryScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * What a launched game's own process carries in its environment to name its game (2026-11-26): the launcher sets it
 * before the game starts, so it holds for a window outside the launch's process tree and under any window class.
 *
 * <ul>
 *   <li>Steam, Proton or native: {@code SteamAppId=<appid>}, seen live on Balatro.</li>
 *   <li>Faugus, any runner: {@code FAUGUSID=<its game id>} ({@code faugus/runner.py}).</li>
 *   <li>Lutris: {@code GAME_NAME=<the game's name in Lutris>} ({@code lutris/game.py}).</li>
 *   <li>BotMaker itself: {@code BOTMAKER_LAUNCH=<one launch's own id>} on what {@link #during} starts, which a game
 *       that inherits it keeps once out of the launch's process tree (Wine's {@code start.exe} gone, a double fork).
 *       A launch handed to a launcher that runs already, or to Flatpak, loses it.</li>
 * </ul>
 *
 * Linux only: elsewhere no process's environment is read, and nothing is marked.
 */
public record GameMarker(Key key, String value) {

    /** The environment variable a marker is, by who sets it. */
    public enum Key {
        STEAM_APP_ID("SteamAppId", "Steam's app id"),
        FAUGUS_ID("FAUGUSID", "Faugus's game id"),
        GAME_NAME("GAME_NAME", "Lutris's game name"),
        BOTMAKER_LAUNCH("BOTMAKER_LAUNCH", "BotMaker's launch");

        private final String id;
        private final String displayName;

        Key(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /** The variable's name in a process's environment. */
        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Faugus's own reading of a GAMEID its entry sets ({@code faugus/core.py}, {@code game_wm_class}). */
    private static final Pattern GAMEID = Pattern.compile("(?:^|\\s)GAMEID=[\"']?(\\S+?)[\"']?(?:\\s|$)");
    private static final Pattern UMU_ID = Pattern.compile("umu-(\\w+)");

    /** The marker {@code target}'s game carries, or {@code null} when its launcher sets none known. */
    public static GameMarker of(LaunchSpec target) {
        if (target == null) return null;
        String token = target.token().trim();
        return switch (target.kind()) {
            case STEAM -> {
                String appId = SteamIds.appId(token);
                yield appId.isEmpty() ? null : new GameMarker(Key.STEAM_APP_ID, appId);
            }
            case FAUGUS -> token.isEmpty() ? null : new GameMarker(Key.FAUGUS_ID, token);
            case LUTRIS -> {
                LutrisLibrary.Game game = LutrisLibrary.known(token);
                yield game == null ? null : lutris(game.name());
            }
            default -> null;
        };
    }

    /**
     * The window class {@code target}'s game is known to open, or {@code null}: Proton names a window
     * {@code steam_app_<id>}, the id of a Steam game, or umu's for a Faugus one. Reads no Lutris library.
     */
    public static String windowClass(LaunchSpec target) {
        if (target == null) return null;
        String id = switch (target.kind()) {
            case STEAM -> SteamIds.appId(target.token().trim());
            case FAUGUS -> {
                Path root = FaugusLibraryScanner.configRoot();
                yield root == null ? null : faugusAppId(read(root.resolve("games.json")), target.token().trim());
            }
            default -> null;
        };
        return id == null || id.isEmpty() ? null : "steam_app_" + id;
    }

    /** A marker of one launch of its own, which {@link #during} sets on what it starts. */
    public static GameMarker launch() {
        return new GameMarker(Key.BOTMAKER_LAUNCH, UUID.randomUUID().toString());
    }

    /** {@code start}'s answer, every process it spawns detached ({@link Spawn#detached}) carrying this marker. */
    public <T> T during(Supplier<T> start) {
        return Spawn.withEnvironment(Map.of(key.id(), value), start);
    }

    /** Whether {@code pid}'s environment names this marker's game. */
    public boolean marks(long pid) {
        return pid > 0 && in(ProcessOrigin.environOf(pid));
    }

    /** Whether {@code environ}, as {@code key=value} entries, holds exactly this marker. Pure. */
    boolean in(String[] environ) {
        String entry = key.id() + "=" + value;
        for (String line : environ) if (line.equals(entry)) return true;
        return false;
    }

    /** The marker Lutris's game named {@code name} carries, or {@code null} for a blank name. */
    static GameMarker lutris(String name) {
        return name == null || name.isBlank() ? null : new GameMarker(Key.GAME_NAME, name);
    }

    /**
     * The id umu gives Faugus's game {@code gameId} in {@code games} (its {@code games.json}): the entry's own
     * {@code GAMEID} or {@code umu-faugus<crc32 of gameId>}, without its {@code umu-}. {@code null} when the entry is
     * not there, runs through Steam, or names no umu id.
     */
    static String faugusAppId(String games, String gameId) {
        JsonNode root = tree(games);
        if (root == null || !root.isArray()) return null;
        for (JsonNode game : root) {
            if (!gameId.equals(game.path("gameid").asText())) continue;
            if ("Steam".equals(game.path("runner").asText())) return null;
            Matcher set = GAMEID.matcher(game.path("launch_arguments").asText(""));
            String umuId = set.find() ? set.group(1) : "umu-faugus" + String.format("%08x", crc32(gameId));
            Matcher umu = UMU_ID.matcher(umuId);
            return umu.matches() ? umu.group(1) : null;
        }
        return null;
    }

    private static long crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static JsonNode tree(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
