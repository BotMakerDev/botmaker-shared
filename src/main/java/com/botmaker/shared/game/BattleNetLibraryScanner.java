package com.botmaker.shared.game;

import com.botmaker.shared.emulator.WindowsRegistry;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.platform.Os;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link GameLibraryProvider} for Battle.net on Windows. Battle.net writes an uninstall entry per game whose
 * {@code UninstallString} runs its uninstaller with the game's {@code --uid=<uid>}; the launch link
 * {@code battlenet://<product>} takes a product code instead ({@code prometheus} is {@code Pro}), so a game is listed
 * only when {@link #PRODUCTS} knows its uid. Each is a {@code url:} target, which Battle.net starts signed in. The
 * picture is the game program's icon ({@code DisplayIcon}). Empty off Windows.
 */
public final class BattleNetLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.URL.id();

    private static final String UNINSTALL = "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

    private static final Pattern UID = Pattern.compile("--uid=([A-Za-z0-9_]+)");

    /** Battle.net's uid → product code, for the games whose code is known (the table Playnite keeps, 2026). */
    static final Map<String, String> PRODUCTS = Map.ofEntries(
            Map.entry("wow", "WoW"), Map.entry("diablo3", "D3"), Map.entry("s2", "S2"), Map.entry("s1", "S1"),
            Map.entry("hs_beta", "WTCG"), Map.entry("heroes", "Hero"), Map.entry("prometheus", "Pro"),
            Map.entry("w3", "W3"), Map.entry("osi", "OSI"), Map.entry("fenris", "Fen"), Map.entry("anbs", "ANBS"),
            Map.entry("viper", "VIPR"), Map.entry("odin", "ODIN"), Map.entry("lazarus", "LAZR"),
            Map.entry("zeus", "ZEUS"), Map.entry("fore", "FORE"), Map.entry("wlby", "WLBY"),
            Map.entry("rtro", "RTRO"), Map.entry("auks", "AUKS"));

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Battle.net"; }

    @Override
    public List<InstalledGame> installedGames() {
        if (!Os.current().isWindows()) return List.of();
        List<InstalledGame> games = new ArrayList<>();
        for (String key : WindowsRegistry.subkeys(UNINSTALL)) {
            Map<String, String> values = WindowsRegistry.values(UNINSTALL + "\\" + key);
            game(values).ifPresent(game -> games.add(new InstalledGame(game.platform(), game.id(), game.name(),
                    icon(values.get("DisplayIcon"), game.id()))));
        }
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(games);
    }

    /**
     * The game an uninstall entry's {@code values} describe, with no picture; empty for an entry that is not a
     * Battle.net game, or one whose product code is unknown. Package-private and pure for the tests.
     */
    static Optional<InstalledGame> game(Map<String, String> values) {
        String uninstall = values.getOrDefault("UninstallString", "");
        if (!uninstall.contains("Battle.net")) return Optional.empty();
        Matcher uid = UID.matcher(uninstall);
        String product = uid.find() ? PRODUCTS.get(uid.group(1).toLowerCase(Locale.ROOT)) : null;
        String name = values.getOrDefault("DisplayName", "").strip();
        if (product == null || name.isBlank()) return Optional.empty();
        return Optional.of(new InstalledGame(PLATFORM, "battlenet://" + product, name, null));
    }

    /** The icon of the program {@code displayIcon} names ({@code "C:\…\Game.exe",0}), or {@code null}. */
    private static Path icon(String displayIcon, String key) {
        if (displayIcon == null || displayIcon.isBlank()) return null;
        String path = displayIcon.strip().replaceFirst(",\\s*-?\\d+$", "").replace("\"", "");
        try {
            return ExeIcons.of(Path.of(path), "battlenet-" + key, p -> p);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
