package com.botmaker.shared.game;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LutrisLibrary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link GameLibraryProvider} for <a href="https://lutris.net/">Lutris</a>: its installed games, read through
 * {@link LutrisLibrary} (the launch stack needs the same rows to tell whether one is running). The id is the
 * library id {@code lutris:rungameid/<id>} starts; the artwork the cover Lutris downloaded.
 *
 * <p><b>Linux's one library</b> (2026-10-29): {@link GameLibraries#pickable} lists only this there. A game Lutris
 * runs through Steam is listed as the Steam game it is ({@code steam:<appid>}), matched by title against Steam's
 * own library: that target is the one a bot written on Windows lists, and Steam says when it runs. A Steam game
 * the match misses is listed as Lutris's, which launches it all the same.
 */
public final class LutrisLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.LUTRIS.id();

    private static final long MATCH_TTL_MS = 60_000;

    /** {@link #steamAppId}'s answers, by Lutris id and title, a miss included. */
    private static final Map<String, Match> STEAM_IDS = new ConcurrentHashMap<>();

    private record Match(Optional<String> appId, long at) {
    }

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Lutris"; }

    /** Every installed Lutris game, sorted by title. Runs {@code lutris} once a minute at most. */
    @Override
    public List<InstalledGame> installedGames() {
        List<LutrisLibrary.Game> rows = List.copyOf(LutrisLibrary.games().values());
        boolean anySteam = rows.stream().anyMatch(LutrisLibrary.Game::throughSteam);
        Map<String, String> steamIds = anySteam ? steamIdsByTitle(new SteamLibraryScanner().installedGames()) : Map.of();
        Set<String> lutrisSteamIds = anySteam ? LutrisLibrary.steamAppIds() : Set.of();
        List<InstalledGame> games = new ArrayList<>();
        for (LutrisLibrary.Game game : rows) games.add(listed(game, steamIds, lutrisSteamIds));
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(games);
    }

    /** By Lutris's own id, whichever way the game is listed: a saved {@code lutris:<id>} still finds its game. */
    @Override
    public Optional<InstalledGame> findById(String id) {
        LutrisLibrary.Game game = LutrisLibrary.find(id);
        return game == null ? Optional.empty() : Optional.of(new InstalledGame(PLATFORM, game.id(), game.name(),
                game.cover()));
    }

    /**
     * The Steam app id of a game Lutris runs through Steam, as {@link #installedGames} lists it; empty for another
     * runner or no match. The launch stack asks this on every "is it running?" poll, so an answer is kept for a
     * minute, by id and title: an id that names another game since is asked again.
     */
    public static Optional<String> steamAppId(LutrisLibrary.Game game) {
        if (game == null || !game.throughSteam()) return Optional.empty();
        String key = game.id() + '|' + game.name();
        Match known = STEAM_IDS.get(key);
        long now = System.currentTimeMillis();
        if (known != null && now - known.at() < MATCH_TTL_MS) return known.appId();
        Optional<String> appId = steamAppId(game, steamIdsByTitle(new SteamLibraryScanner().installedGames()),
                LutrisLibrary.steamAppIds());
        STEAM_IDS.put(key, new Match(appId, now));
        return appId;
    }

    /**
     * {@code game}'s Steam app id: the Steam game of its {@link #title}, when Lutris has a Steam config of that app
     * id too ({@code lutrisSteamIds}, empty when unreadable: then the title alone). Lutris stores the exact app id
     * only in its database, which this module cannot read, so the title finds it and Lutris's configs confirm it.
     * Pure.
     */
    static Optional<String> steamAppId(LutrisLibrary.Game game, Map<String, String> steamIdsByTitle,
                                       Set<String> lutrisSteamIds) {
        if (!game.throughSteam()) return Optional.empty();
        String appId = steamIdsByTitle.get(title(game.name()));
        return appId != null && (lutrisSteamIds.isEmpty() || lutrisSteamIds.contains(appId))
                ? Optional.of(appId) : Optional.empty();
    }

    /** {@code game} as the picker lists it: the Steam game it runs when {@link #steamAppId} finds one. */
    static InstalledGame listed(LutrisLibrary.Game game, Map<String, String> steamIdsByTitle,
                                Set<String> lutrisSteamIds) {
        return steamAppId(game, steamIdsByTitle, lutrisSteamIds)
                .map(appId -> new InstalledGame(SteamLibraryScanner.PLATFORM, appId, game.name(), game.cover()))
                .orElseGet(() -> new InstalledGame(PLATFORM, game.id(), game.name(), game.cover()));
    }

    /**
     * Steam's store games by {@link #title}; a title two games share maps to neither, since either guess could be
     * wrong. A non-Steam shortcut (a 64-bit game id, Lutris's own "Create Steam shortcut" among them) is no store
     * game and is left out, or it would collide with the real one.
     */
    static Map<String, String> steamIdsByTitle(List<InstalledGame> steam) {
        Map<String, String> byTitle = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (InstalledGame game : steam) {
            String key = title(game.name());
            if (key.isEmpty() || !storeAppId(game.id())) continue;
            if (seen.merge(key, 1, Integer::sum) == 1) byTitle.put(key, game.id());
            else byTitle.remove(key);
        }
        return byTitle;
    }

    /** Whether {@code id} is a store app id: a number below 2³², where a shortcut's game id is 64-bit. */
    static boolean storeAppId(String id) {
        try {
            long value = Long.parseLong(id);
            return value > 0 && value < (1L << 32);
        } catch (NumberFormatException | NullPointerException notANumber) {
            return false;
        }
    }

    /** A title compared without case, punctuation or spacing: Lutris and Steam spell a dash or a colon apart. */
    static String title(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
