package com.botmaker.shared.game;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LutrisLibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link GameLibraryProvider} for <a href="https://lutris.net/">Lutris</a>: its installed games, read through
 * {@link LutrisLibrary} (the launch stack needs the same rows to tell whether one is running). The id is the
 * library id {@code lutris:rungameid/<id>} starts; the artwork the cover Lutris downloaded.
 */
public final class LutrisLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.LUTRIS.id();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Lutris"; }

    /** Every installed Lutris game, sorted by title. Runs {@code lutris} once a minute at most. */
    @Override
    public List<InstalledGame> installedGames() {
        List<InstalledGame> games = new ArrayList<>();
        for (LutrisLibrary.Game game : LutrisLibrary.games().values()) {
            games.add(new InstalledGame(PLATFORM, game.id(), game.name(), game.cover()));
        }
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(games);
    }
}
