package com.botmaker.shared.game;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.platform.Os;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * {@link GameLibraryProvider} for the itch app on Windows: each game it installed is a folder under
 * {@code %APPDATA%\itch\apps} with a receipt ({@code .itch\receipt.json.gz}) that names it. itch games carry no DRM
 * and need no launcher, so each is an {@code exe:} target, the folder's largest program that is not a helper
 * ({@link GamePrograms}); its picture is that program's icon. Empty off Windows.
 */
public final class ItchLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = LaunchKind.EXE.id();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "itch"; }

    @Override
    public List<InstalledGame> installedGames() {
        String appData = System.getenv("APPDATA");
        if (!Os.current().isWindows() || appData == null || appData.isBlank()) return List.of();
        return gamesIn(Path.of(appData, "itch", "apps"));
    }

    /** The games installed under {@code apps}, by title. Package-private for the tests. */
    static List<InstalledGame> gamesIn(Path apps) {
        List<InstalledGame> games = new ArrayList<>();
        if (!Files.isDirectory(apps)) return games;
        try (Stream<Path> folders = Files.list(apps)) {
            folders.filter(Files::isDirectory).sorted().forEach(folder -> {
                String title = title(folder.resolve(".itch").resolve("receipt.json.gz"));
                Path program = title == null ? null : GamePrograms.largest(GamePrograms.under(folder), p -> false);
                if (program == null) return;
                games.add(new InstalledGame(PLATFORM, program.toString(), title,
                        ExeIcons.of(program, "itch-" + folder.getFileName(), p -> p)));
            });
        } catch (Exception e) {
            // what was read stands
        }
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(games);
    }

    /** The game's title from its gzipped receipt ({@code game.title}), or {@code null}. */
    static String title(Path receipt) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(receipt))) {
            JsonNode title = MAPPER.readTree(in).path("game").path("title");
            return title.isTextual() && !title.asText().isBlank() ? title.asText().strip() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
