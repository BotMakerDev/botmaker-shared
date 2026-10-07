package com.botmaker.shared.game;

import com.botmaker.shared.launch.DesktopEntries;
import com.botmaker.shared.launch.LaunchKind;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link GameLibraryProvider} for the desktop's application menu ({@link DesktopEntries}): every app it lists
 * that is not a shortcut to another launcher's game — that game is listed under its launcher instead. The id is
 * the desktop-entry id {@code gtk-launch} takes, the artwork the entry's icon.
 *
 * <p>Games and every other app alike, so a saved {@code desktop:} target always resolves to its name; a picker
 * that wants the games first asks {@link DesktopEntries.Entry#game()} itself.
 */
public final class DesktopEntryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.DESKTOP.id();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Apps"; }

    @Override
    public List<InstalledGame> installedGames() {
        List<InstalledGame> apps = new ArrayList<>();
        for (DesktopEntries.Entry entry : DesktopEntries.list()) {
            if (entry.shortcutFor().isPresent()) continue;
            apps.add(new InstalledGame(PLATFORM, entry.id(), entry.name(), DesktopEntries.icon(entry)));
        }
        return List.copyOf(apps);
    }
}
