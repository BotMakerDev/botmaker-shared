package com.botmaker.shared.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@link GameLibraryProvider} for the Epic Games Launcher: discovers installed games by reading the
 * launcher's on-disk manifests — no login, no Web API, no network. Mirrors {@link SteamLibraryScanner}.
 *
 * <p>Epic writes one JSON manifest per installed game under
 * {@code %ProgramData%\Epic\EpicGamesLauncher\Data\Manifests\*.item}. Each manifest carries the game's
 * {@code AppName} (the launch token handed to {@code Game.launchEpic(...)} via the
 * {@code com.epicgames.launcher://apps/<AppName>?action=launch} URL) and {@code DisplayName} (the title).
 * Only genuinely-installed games have a manifest, so this is exactly the launchable set.
 *
 * <p>Every step is best-effort: any missing directory / unparseable manifest is skipped and an empty list
 * is the worst case — this never throws. Epic keeps no local portrait cover art in a stable path, so
 * {@link InstalledGame#artwork()} is the game program's own icon ({@link ExeIcons}, the program found from the
 * manifest's {@code InstallLocation} and {@code LaunchExecutable} by {@link #iconProgram}), or {@code null} off
 * Windows and the picker shows
 * initials. Windows-only in practice (the launcher only ships on Windows/macOS); returns empty elsewhere.
 */
public final class EpicLibraryScanner implements GameLibraryProvider {

    public static final String PLATFORM = "epic";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String platform() { return PLATFORM; }

    @Override public String displayName() { return "Epic Games"; }

    /** All locally-installed Epic games, deduplicated by AppName and sorted by title. Never throws. */
    @Override
    public List<InstalledGame> installedGames() {
        return gamesIn(manifestsDir());
    }

    /**
     * The scan itself, against an explicit manifests directory. Split out from {@link #installedGames()}
     * because {@link #manifestsDir()} reads {@code PROGRAMDATA} and returns null off Windows, which would
     * leave the parsing untestable on every machine that runs the suite.
     *
     * @param manifests Epic's {@code Manifests} directory, or {@code null} when the launcher isn't installed
     */
    static List<InstalledGame> gamesIn(Path manifests) {
        Map<String, InstalledGame> byId = new LinkedHashMap<>();
        try {
            if (manifests == null) return List.of();
            try (Stream<Path> items = Files.list(manifests)) {
                items.filter(EpicLibraryScanner::isManifest)
                        .forEach(item -> parseManifest(item)
                                .ifPresent(g -> byId.putIfAbsent(g.id(), g)));
            }
        } catch (Exception ignored) {
            // never propagate: the picker degrades to free-text entry
        }
        List<InstalledGame> games = new ArrayList<>(byId.values());
        games.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return games;
    }

    /** Every {@code .item} manifest of this computer's Epic Games Launcher, unread. Never throws. */
    public static List<Path> manifests() {
        Path dir = manifestsDir();
        if (dir == null) return List.of();
        try (Stream<Path> items = Files.list(dir)) {
            return items.filter(EpicLibraryScanner::isManifest).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isManifest(Path p) {
        return p.getFileName().toString().endsWith(".item");
    }

    /** The Epic manifests directory for this OS, or {@code null} if the launcher isn't installed. */
    private static Path manifestsDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String programData = System.getenv("PROGRAMDATA");
            if (programData != null && !programData.isBlank()) {
                candidates.add(Path.of(programData, "Epic", "EpicGamesLauncher", "Data", "Manifests"));
            }
            candidates.add(Path.of("C:\\ProgramData\\Epic\\EpicGamesLauncher\\Data\\Manifests"));
        } else if (os.contains("mac")) {
            candidates.add(Path.of(System.getProperty("user.home", ""),
                    "Library", "Application Support", "Epic", "EpicGamesLauncher", "Data", "Manifests"));
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c)) return c;
        }
        return null;
    }

    /** Reads {@code AppName} + {@code DisplayName} from one {@code .item} manifest. */
    private static java.util.Optional<InstalledGame> parseManifest(Path item) {
        try {
            JsonNode root = MAPPER.readTree(item.toFile());
            String appName = text(root, "AppName");
            if (appName == null || appName.isBlank()) return java.util.Optional.empty();
            String name = text(root, "DisplayName");
            if (name == null || name.isBlank()) name = appName;
            return java.util.Optional.of(new InstalledGame(PLATFORM, appName, name,
                    ExeIcons.of(executable(root), PLATFORM + "-" + appName, EpicLibraryScanner::iconProgram)));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /** The game's program, {@code InstallLocation} joined with {@code LaunchExecutable}, or {@code null}. */
    static Path executable(JsonNode manifest) {
        String dir = text(manifest, "InstallLocation");
        String exe = text(manifest, "LaunchExecutable");
        if (dir == null || dir.isBlank() || exe == null || exe.isBlank()) return null;
        try {
            return Path.of(dir).resolve(exe);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The program whose icon stands for the game: {@code launch} itself, unless it is Epic's online-services
     * bootstrapper, which many games launch through and whose icon is Epic's logo on every one of them. Then the
     * game's own program beside it: the bootstrapper's name without its {@code Eos} ({@code FirestoneEos.exe} →
     * {@code Firestone.exe}), an Unreal {@code *-Shipping.exe} (one starting with that name first), else the
     * largest program left once crash reporters, installers and helpers are set aside. {@code null} when there is
     * none. A folder it can't read is skipped, not the end of the search.
     */
    static Path iconProgram(Path launch) {
        if (launch == null || !isBootstrapper(launch)) return launch;
        Path dir = launch.getParent();
        if (dir == null) return null;
        String stem = stem(launch).replaceFirst("(?i)eos$", "");
        List<Path> programs = programsUnder(dir).stream().filter(p -> !p.equals(launch)).toList();
        for (Path p : programs) if (stem(p).equalsIgnoreCase(stem)) return p;
        Path shipping = null;
        for (Path p : programs) {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith("-shipping.exe")) continue;
            if (name.startsWith(stem.toLowerCase(Locale.ROOT))) return p;
            if (shipping == null) shipping = p;
        }
        if (shipping != null) return shipping;
        Path largest = null;
        long most = -1;
        for (Path p : programs) {
            if (HELPER.matcher(p.getFileName().toString()).find()) continue;
            long size = size(p);
            if (size > most && !isBootstrapper(p)) {
                largest = p;
                most = size;
            }
        }
        return largest;
    }

    /** Every {@code .exe} up to {@link #PROGRAM_DEPTH} below {@code dir}, in name order; unreadable folders skipped. */
    private static List<Path> programsUnder(Path dir) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), PROGRAM_DEPTH,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file,
                                java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()
                                    && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                                found.add(file);
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path file, java.io.IOException e) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Exception e) {
            // what was found before the failure still counts
        }
        found.sort(java.util.Comparator.comparing(Path::toString));
        return found;
    }

    /** How deep a game's own program sits below its install folder: Unreal puts it at {@code <Game>/Binaries/Win64}. */
    private static final int PROGRAM_DEPTH = 4;

    /**
     * Programs a game folder carries that are not the game, by their usual names; only the largest-program
     * fallback sets them aside, so a game called "Crash…" is still found by its name.
     */
    private static final java.util.regex.Pattern HELPER = java.util.regex.Pattern.compile(
            "(?i)^(UnityCrashHandler|CrashReportClient|EpicOnlineServices|EpicWebHelper|UE4?PrereqSetup|unins)"
                    + "|redist|^setup|^dxsetup");

    /** The UTF-16 assembly name every Epic online-services bootstrapper carries. */
    private static final byte[] BOOTSTRAPPER_MARK =
            "EpicOnlineServices.BootStrapper".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);

    /** A bootstrapper is a few MB; a file far larger is a game, and is not read whole to find out. */
    private static final long BOOTSTRAPPER_MAX_BYTES = 16L << 20;

    /** Whether {@code exe} is Epic's online-services bootstrapper rather than a game's own program. */
    static boolean isBootstrapper(Path exe) {
        try {
            if (!Files.isRegularFile(exe) || Files.size(exe) > BOOTSTRAPPER_MAX_BYTES) return false;
            byte[] bytes = Files.readAllBytes(exe);
            outer:
            for (int i = 0; i <= bytes.length - BOOTSTRAPPER_MARK.length; i++) {
                for (int j = 0; j < BOOTSTRAPPER_MARK.length; j++) {
                    if (bytes[i + j] != BOOTSTRAPPER_MARK[j]) continue outer;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stem(Path exe) {
        String name = exe.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
