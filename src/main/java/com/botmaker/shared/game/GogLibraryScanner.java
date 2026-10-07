package com.botmaker.shared.game;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.platform.Os;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link GameLibraryProvider} for GOG games installed on Windows, from the registry key the GOG installers write
 * ({@code HKLM\SOFTWARE\WOW6432Node\GOG.com\Games}, one subkey per game with {@code gameName} and {@code exe}).
 *
 * <p>A GOG game needs no launcher — GOG Galaxy is optional and its installers are DRM-free — so each is an
 * {@code exe:} target: its {@link #platform()} is {@code exe}, and a saved {@code exe:<path>} target resolves
 * back to the game's title through this scanner. On Linux GOG games come through Heroic or Lutris instead, and
 * this lists nothing.
 */
public final class GogLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.EXE.id();

    private static final String GAMES_KEY = "HKLM\\SOFTWARE\\WOW6432Node\\GOG.com\\Games";

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "GOG"; }

    @Override
    public List<InstalledGame> installedGames() {
        if (!Os.current().isWindows()) return List.of();
        try {
            Spawn.Completed query = Spawn.run(QUERY_TIMEOUT, "reg", "query", GAMES_KEY, "/s");
            return query == null ? List.of() : parse(query.output());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * {@code reg query /s}'s output as games: a block per subkey, each value on a line as
     * {@code name    REG_SZ    data}. A block with no {@code exe} is skipped. Package-private and pure for the
     * tests.
     */
    static List<InstalledGame> parse(String output) {
        Map<String, InstalledGame> byExe = new LinkedHashMap<>();
        String name = null;
        String exe = null;
        for (String line : (output == null ? "" : output).split("\\R")) {
            if (line.startsWith("HKEY_")) {
                add(byExe, name, exe);
                name = null;
                exe = null;
                continue;
            }
            String[] parts = line.strip().split("\\s{2,}", 3);
            if (parts.length < 3 || !parts[1].startsWith("REG_")) continue;
            if (parts[0].equalsIgnoreCase("gameName")) name = parts[2].strip();
            if (parts[0].equalsIgnoreCase("exe")) exe = parts[2].strip();
        }
        add(byExe, name, exe);
        List<InstalledGame> games = new ArrayList<>(byExe.values());
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(games);
    }

    private static void add(Map<String, InstalledGame> into, String name, String exe) {
        if (exe == null || exe.isBlank()) return;
        into.putIfAbsent(exe, new InstalledGame(PLATFORM, exe, name == null || name.isBlank() ? exe : name, null));
    }
}
