package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Heroic knows about the games it installed, read straight off its on-disk config — no login, no Web API,
 * no network.
 *
 * <p>It lives in shared because two very different questions need the same answer. {@link Launcher#isRunning}
 * needs it because a {@code heroic:} target's token is Heroic's <em>app name</em>, which for an Epic game is an
 * opaque hash: nothing in a running game's command line or window title contains it. Newer Heroic launches the
 * game in-process rather than spawning {@code legendary launch <appName>}, so the live process is
 * {@code wine}/{@code umu-run}/{@code proton} carrying the game's <em>executable path</em>, and the window is
 * named after its <em>title</em> — which is exactly why detection worked only sometimes. Studio's game picker
 * needs the same records to list installed games with their titles. Two readers of one config file drift; one
 * doesn't.
 *
 * <p>Parsing is regex/brace-scanning rather than Jackson, deliberately: shared has no JSON dependency (see
 * {@code LdPlayerPlatform} for the same call), and everything read here is a handful of string fields out of a
 * flat object. Every step is best-effort and total — a missing root, an unreadable file or a shape Heroic
 * changed yields fewer entries, never an exception, because the caller's fallback (launch it anyway, or show an
 * empty list) is always safe.
 */
public final class HeroicLibrary {

    /**
     * One installed game. {@code installPath} and {@code executable} are whatever Heroic recorded — an absolute
     * directory and an often-relative binary path — and may be blank for a store/entry shape that doesn't carry
     * them.
     */
    public record Game(String appName, String title, String installPath, String executable) {

        /**
         * The strings a <em>live</em> incarnation of this game plausibly carries, most distinctive first: the
         * executable's file name and the install directory (what the Wine/Proton wrapper has in argv), the app
         * name (what an older {@code legendary launch} spawn has), and the title (what the window is called).
         *
         * <p>Tokens shorter than {@link #MIN_TOKEN} are dropped — a two-letter title matches half the process
         * table, and a false "already running" is indistinguishable from a launch that silently did nothing.
         */
        public List<String> runningTokens() {
            List<String> tokens = new ArrayList<>();
            addToken(tokens, fileName(executable));
            addToken(tokens, installPath);
            addToken(tokens, appName);
            addToken(tokens, title);
            return List.copyOf(tokens);
        }

        private static void addToken(List<String> into, String token) {
            if (token == null || token.isBlank() || token.trim().length() < MIN_TOKEN) {
                return;
            }
            String trimmed = token.trim();
            if (into.stream().noneMatch(t -> t.equalsIgnoreCase(trimmed))) {
                into.add(trimmed);
            }
        }
    }

    /** Shortest string allowed to stand as evidence that a game is running. */
    private static final int MIN_TOKEN = 3;

    /** How long a parse of Heroic's config is reused; a poll loop shouldn't re-read three files per tick. */
    private static final long CACHE_TTL_MS = 10_000;

    private static final Pattern INSTALLED_ARRAY = Pattern.compile("\"installed\"\\s*:\\s*\\[");
    private static final Pattern GAMES_ARRAY = Pattern.compile("\"games\"\\s*:\\s*\\[");

    private static volatile Map<String, Game> cached;
    private static volatile long cachedAt;

    private HeroicLibrary() {}

    /**
     * Every installed game Heroic knows about, keyed by app name. Both config roots are read (native first, so
     * a native install wins over a Flatpak one for a game present in both).
     */
    public static Map<String, Game> games() {
        Map<String, Game> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return snapshot;
        }
        Map<String, Game> games = new LinkedHashMap<>();
        for (Path root : configRoots()) {
            readEpic(root, games);
            readGog(root, games);
            readSideloaded(root, games);
        }
        Map<String, Game> result = Map.copyOf(games);
        cached = result;
        cachedAt = System.currentTimeMillis();
        return result;
    }

    /** The game Heroic installed under {@code appName}, or {@code null} when it knows of no such app. */
    public static Game find(String appName) {
        return appName == null || appName.isBlank() ? null : games().get(appName.trim());
    }

    /**
     * {@link Game#runningTokens()} for {@code appName}, or just the app name itself when Heroic's config can't
     * be read — the pre-existing behaviour, so an unreadable config degrades to what {@link Launcher} did
     * before rather than to nothing at all.
     */
    public static List<String> runningTokens(String appName) {
        Game game = find(appName);
        if (game != null) {
            return game.runningTokens();
        }
        return appName == null || appName.isBlank() ? List.of() : List.of(appName.trim());
    }

    /**
     * Heroic's config directories that actually exist, native before Flatpak. Public because Studio resolves
     * cached artwork under {@code <root>/icons/} and must look in the same places this parsed.
     */
    public static List<Path> configRoots() {
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) {
            return List.of();
        }
        List<Path> roots = new ArrayList<>();
        for (Path candidate : List.of(
                Path.of(home, ".config", "heroic"),
                Path.of(home, ".var", "app", "com.heroicgameslauncher.hgl", "config", "heroic"))) {
            if (Files.isDirectory(candidate)) {
                roots.add(candidate);
            }
        }
        return List.copyOf(roots);
    }

    /** Forget the parsed config — for tests, and for a caller that just installed something. */
    public static void invalidate() {
        cached = null;
    }

    // --- Per-store readers ------------------------------------------------------------------------------

    /** Epic: {@code legendaryConfig/legendary/installed.json} is one object per app name, keyed by app name. */
    private static void readEpic(Path root, Map<String, Game> into) {
        String json = read(root.resolve("legendaryConfig/legendary/installed.json"));
        if (json == null) {
            return;
        }
        for (String entry : objectsIn(json, containerStart(json, '{'))) {
            add(into, field(entry, "app_name"), field(entry, "title"),
                    field(entry, "install_path"), field(entry, "executable"));
        }
    }

    /** GOG: an {@code installed} array of install records, with titles from the separate library cache. */
    private static void readGog(Path root, Map<String, Game> into) {
        String json = read(root.resolve("gog_store/installed.json"));
        if (json == null) {
            return;
        }
        Map<String, String> titles = gogTitles(root);
        for (String entry : objectsIn(json, arrayStart(json, INSTALLED_ARRAY))) {
            String appName = field(entry, "appName");
            add(into, appName, titles.get(appName), field(entry, "install_path"), field(entry, "executable"));
        }
    }

    /** appName → title from {@code store_cache/gog_library.json} (the install records carry no title). */
    private static Map<String, String> gogTitles(Path root) {
        Map<String, String> titles = new LinkedHashMap<>();
        String json = read(root.resolve("store_cache/gog_library.json"));
        if (json == null) {
            return titles;
        }
        // The cache is either a bare array of games or an object with a "games" array; accept both.
        int start = arrayStart(json, GAMES_ARRAY);
        if (start < 0) {
            start = containerStart(json, '[');
        }
        for (String entry : objectsIn(json, start)) {
            String appName = firstNonBlank(field(entry, "app_name"), field(entry, "appName"));
            String title = field(entry, "title");
            if (appName != null && title != null) {
                titles.putIfAbsent(appName, title);
            }
        }
        return titles;
    }

    /** Sideloaded apps: {@code sideload_apps/library.json} carries a {@code games} array. */
    private static void readSideloaded(Path root, Map<String, Game> into) {
        String json = read(root.resolve("sideload_apps/library.json"));
        if (json == null) {
            return;
        }
        for (String entry : objectsIn(json, arrayStart(json, GAMES_ARRAY))) {
            add(into, firstNonBlank(field(entry, "app_name"), field(entry, "appName")),
                    field(entry, "title"), field(entry, "install_path"), field(entry, "executable"));
        }
    }

    /** First root wins: a game installed both natively and under Flatpak is one game, described once. */
    private static void add(Map<String, Game> into, String appName, String title,
                            String installPath, String executable) {
        if (appName == null || appName.isBlank()) {
            return;
        }
        into.putIfAbsent(appName, new Game(appName,
                title == null || title.isBlank() ? appName : title,
                installPath == null ? "" : installPath,
                executable == null ? "" : executable));
    }

    // --- Minimal JSON scanning --------------------------------------------------------------------------

    /**
     * The top-level objects directly inside the container that opens at {@code openIndex} — the game records,
     * whether the container is an array of them or an object keyed by app name. Nested objects (a sideloaded
     * entry's {@code install}) stay inside their parent's text, where {@link #field} still finds their fields.
     *
     * <p>The scan tracks string state so a brace inside a Windows path or a game title can't unbalance it.
     */
    private static List<String> objectsIn(String json, int openIndex) {
        if (openIndex < 0) {
            return List.of();
        }
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openIndex + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                }
                case '}' -> {
                    if (depth == 0) {
                        // The container itself closed — everything after belongs to something else.
                        return List.copyOf(objects);
                    }
                    if (--depth == 0 && start >= 0) {
                        objects.add(json.substring(start, i + 1));
                        start = -1;
                    }
                }
                case ']' -> {
                    if (depth == 0) {
                        return List.copyOf(objects);
                    }
                }
                default -> { }
            }
        }
        return List.copyOf(objects);
    }

    /** Index of the first {@code open} character in the document, or -1. */
    private static int containerStart(String json, char open) {
        return json.indexOf(open);
    }

    /** Index of the {@code [} that {@code field} introduces, or -1 when the field isn't there. */
    private static int arrayStart(String json, Pattern field) {
        Matcher m = field.matcher(json);
        return m.find() ? m.end() - 1 : -1;
    }

    /**
     * A string field's value, unescaped enough for paths ({@code \\} and {@code \"}), or {@code null}. Non-string
     * values are ignored, which is what makes reading a flat record out of a nested object safe: only the fields
     * we name are read, and the rest of the shape can change freely.
     */
    private static String field(String object, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(object);
        if (!m.find()) {
            return null;
        }
        return m.group(1).replace("\\\\", "\\").replace("\\\"", "\"").replace("\\/", "/");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b == null || b.isBlank() ? null : b;
    }

    /** A path's trailing segment, for either separator — Heroic records Windows paths for Wine games. */
    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 && slash < trimmed.length() - 1 ? trimmed.substring(slash + 1) : trimmed;
    }

    /** File contents, or {@code null} when it is missing or unreadable. Never throws. */
    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            Diag.log("[Target] reading " + file + " failed: " + e.getMessage());
            return null;
        }
    }

    /** Test seam: parse a document the way {@link #readEpic} does, without touching the filesystem. */
    static List<Game> parseEpicForTesting(String json) {
        Map<String, Game> games = new LinkedHashMap<>();
        for (String entry : objectsIn(json, containerStart(json, '{'))) {
            add(games, field(entry, "app_name"), field(entry, "title"),
                    field(entry, "install_path"), field(entry, "executable"));
        }
        return List.copyOf(games.values());
    }

    /** Test seam: {@link #readSideloaded}'s parse, filesystem-free. */
    static List<Game> parseSideloadedForTesting(String json) {
        Map<String, Game> games = new LinkedHashMap<>();
        for (String entry : objectsIn(json, arrayStart(json, GAMES_ARRAY))) {
            add(games, firstNonBlank(field(entry, "app_name"), field(entry, "appName")),
                    field(entry, "title"), field(entry, "install_path"), field(entry, "executable"));
        }
        return List.copyOf(games.values());
    }
}
