package com.botmaker.shared.game;

import com.botmaker.shared.emulator.WindowsRegistry;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link GameLibraryProvider} for Ubisoft Connect on Windows: the games under its registry key
 * {@code HKLM\SOFTWARE\WOW6432Node\Ubisoft\Launcher\Installs\<id>} ({@code InstallDir}), named by the uninstall
 * entry Ubisoft Connect writes beside each ({@code Uplay Install <id>}), else by their folder. Each is a
 * {@code url:} target, {@code uplay://launch/<id>/0} ({@link LaunchSpec#ubisoft}), which Ubisoft Connect starts
 * signed in. Empty off Windows.
 */
public final class UbisoftLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.URL.id();

    private static final String INSTALLS = "HKLM\\SOFTWARE\\WOW6432Node\\Ubisoft\\Launcher\\Installs";
    private static final String UNINSTALL = "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Ubisoft Connect"; }

    @Override
    public List<InstalledGame> installedGames() {
        List<InstalledGame> games = new ArrayList<>();
        for (String id : WindowsRegistry.subkeys(INSTALLS)) {
            game(id, WindowsRegistry.read(INSTALLS + "\\" + id, "InstallDir"),
                    WindowsRegistry.read(UNINSTALL + "\\Uplay Install " + id, "DisplayName"))
                    .ifPresent(games::add);
        }
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(games);
    }

    /**
     * The game Ubisoft Connect installed as {@code id} in {@code installDir}, called {@code displayName} or else
     * after its folder; empty for an id that is no number or a game with no folder. Package-private and pure for the
     * tests.
     */
    static Optional<InstalledGame> game(String id, String installDir, String displayName) {
        if (id == null || !id.strip().matches("\\d+") || installDir == null || installDir.isBlank()) {
            return Optional.empty();
        }
        String name = displayName != null && !displayName.isBlank() ? displayName.strip() : folderName(installDir);
        return Optional.of(new InstalledGame(PLATFORM, LaunchSpec.ubisoft(id.strip()).token(), name, null));
    }

    private static String folderName(String dir) {
        String trimmed = dir.strip().replaceAll("[/\\\\]+$", "");
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }
}
