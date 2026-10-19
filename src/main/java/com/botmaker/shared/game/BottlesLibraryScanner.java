package com.botmaker.shared.game;

import com.botmaker.shared.launch.BottlesLibrary;
import com.botmaker.shared.launch.LaunchKind;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link GameLibraryProvider} for <a href="https://usebottles.com/">Bottles</a>: the programs its bottles list
 * ({@link BottlesLibrary}). The id is {@code <bottle>/<program>}; a program has no picture Bottles keeps.
 */
public final class BottlesLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.BOTTLES.id();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Bottles"; }

    @Override
    public List<InstalledGame> installedGames() {
        List<InstalledGame> games = new ArrayList<>();
        for (BottlesLibrary.Program program : BottlesLibrary.programs()) {
            // Named after its bottle too: two bottles often hold a program of the same name ("Battle.net").
            games.add(new InstalledGame(PLATFORM, program.spec().token(),
                    program.name() + " (" + program.bottle() + ")", null));
        }
        return List.copyOf(games);
    }
}
