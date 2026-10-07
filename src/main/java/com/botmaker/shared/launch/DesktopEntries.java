package com.botmaker.shared.launch;

import com.botmaker.shared.emulator.WaydroidPlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The applications the desktop's menu lists — the {@code .desktop} files under each XDG data directory's
 * {@code applications/} — read the way a menu reads them: the first file for an id wins, and an entry the menu
 * hides ({@code NoDisplay}, {@code Hidden}, not an {@code Application}) is skipped.
 *
 * <p>Many entries are shortcuts a launcher wrote for one of its own games — Steam's
 * {@code steam steam://rungameid/2379780}, Faugus's {@code faugus-launcher --game ea-app}, Waydroid's
 * {@code waydroid app launch com.x}. {@link Entry#shortcutFor()} recognises those as that launcher's target, so
 * a picker can show the game once, under its launcher, and launch it the launcher's way.
 *
 * <p>Linux only in practice: elsewhere no directory exists and the list is empty.
 */
public final class DesktopEntries {

    private static final Pattern STEAM = Pattern.compile("steam://rungameid/(\\d+)");
    private static final Pattern FAUGUS = Pattern.compile("faugus-launcher\\S*\\s+--game\\s+(\\S+)");
    private static final Pattern LUTRIS = Pattern.compile("lutris:rungameid/(\\d+)");
    private static final Pattern HEROIC_QUERY = Pattern.compile("heroic://launch\\?[^\\s\"']*appName=([^&\\s\"']+)");
    private static final Pattern HEROIC_PATH = Pattern.compile("heroic://launch/(?:[^/\\s\"']+/)?([^/?\\s\"']+)");
    private static final Pattern WAYDROID = Pattern.compile("waydroid\\s+app\\s+launch\\s+(\\S+)");

    /** Icon sizes tried for a themed icon, largest first; an SVG is skipped, JavaFX can't draw one. */
    private static final List<String> ICON_SIZES = List.of("512x512", "256x256", "128x128", "96x96", "64x64",
            "48x48");

    private DesktopEntries() {}

    /**
     * One menu entry.
     *
     * @param id         the desktop-entry id: the file's path under {@code applications/}, {@code /} as {@code -},
     *                   without {@code .desktop} — what {@code gtk-launch} takes
     * @param name       the untranslated {@code Name}
     * @param exec       the {@code Exec} line as written
     * @param icon       the {@code Icon} value: a theme name or an absolute path; may be blank
     * @param categories the {@code Categories}
     * @param flatpakId  {@code X-Flatpak}, the app id of a Flatpak's export, or blank
     */
    public record Entry(String id, String name, String exec, String icon, Set<String> categories,
                        String flatpakId) {

        /** Whether the menu files it under games. */
        public boolean game() {
            return categories.contains("Game");
        }

        /**
         * The launcher target this entry is a shortcut for — a Steam, Faugus, Lutris or Heroic game, or a Waydroid
         * app — or empty when it is an application of its own.
         */
        public Optional<LaunchSpec> shortcutFor() {
            return shortcutIn(exec);
        }

        /** The target that starts this entry: its launcher's when it is a shortcut, else {@code desktop:<id>}. */
        public LaunchSpec spec() {
            return shortcutFor().orElseGet(() -> new LaunchSpec(LaunchKind.DESKTOP, id));
        }
    }

    /**
     * Every entry the menu would show, data-home first; empty off Linux. Reads files: not on a UI thread. Cached
     * for a minute, because "is it running?" asks on every poll.
     */
    public static List<Entry> list() {
        List<Entry> snapshot = cached;
        if (snapshot != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return snapshot;
        List<Entry> entries = read();
        cached = entries;
        cachedAt = System.currentTimeMillis();
        return entries;
    }

    private static final long CACHE_TTL_MS = 60_000;
    private static volatile List<Entry> cached;
    private static volatile long cachedAt;

    /**
     * Programs that run something else — a shell, an interpreter, Wine, a sandbox — so their name says nothing
     * about which app is running: a {@code java} or {@code sh} is always somewhere on the machine.
     */
    private static final Set<String> GENERIC_PROGRAMS = Set.of("sh", "bash", "dash", "zsh", "env", "python",
            "python3", "java", "wine", "wine64", "flatpak", "snap", "xdg-open", "gio", "gtk-launch", "steam",
            "mono", "node", "electron", "umu-run", "proton", "bwrap", "pkexec", "sudo", "kioclient", "kde-open");

    private static List<Entry> read() {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Path dataDir : dataDirs()) {
            Path applications = dataDir.resolve("applications");
            if (!Files.isDirectory(applications)) continue;
            try (Stream<Path> files = Files.walk(applications, 4)) {
                files.filter(f -> f.getFileName().toString().endsWith(".desktop"))
                        .forEach(file -> {
                            String id = idOf(applications, file);
                            if (byId.containsKey(id)) return;
                            // An id the first directory hides is hidden everywhere, so it is claimed even when
                            // the entry itself is skipped.
                            byId.put(id, parse(id, read(file)).orElse(null));
                        });
            } catch (IOException | RuntimeException unreadable) {
                // one unreadable directory hides its entries, not the rest of the menu
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : byId.values()) if (entry != null) entries.add(entry);
        entries.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(entries);
    }

    /** The entry with desktop id {@code id}, or empty. */
    public static Optional<Entry> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String needle = id.trim();
        return list().stream().filter(e -> e.id().equals(needle)).findFirst();
    }

    /**
     * What a running {@code desktop:<id>} carries: its program's file name, or a Flatpak's app id since its
     * program is {@code flatpak}. Nothing for a program that only runs another ({@link #GENERIC_PROGRAMS}), and
     * never the entry's name: "Files" or "Chess" would match any command line or window that mentions them, and
     * a run would skip starting an app that is not up.
     */
    public static List<String> runningTokens(String id) {
        return find(id).map(DesktopEntries::tokensOf).orElse(List.of());
    }

    /** {@link #runningTokens} for one entry. Package-private and pure for the tests. */
    static List<String> tokensOf(Entry entry) {
        if (!entry.flatpakId().isBlank()) return List.of(entry.flatpakId());
        String program = programOf(entry.exec());
        return program.isBlank() || GENERIC_PROGRAMS.contains(program.toLowerCase(java.util.Locale.ROOT))
                ? List.of() : List.of(program);
    }

    /**
     * The picture for {@code entry}: its icon when that is a path, else the largest PNG of that name in the
     * hicolor theme or {@code pixmaps}; {@code null} when there is none.
     */
    public static Path icon(Entry entry) {
        String icon = entry.icon();
        if (icon.isBlank()) return null;
        if (icon.startsWith("/")) {
            Path path = Path.of(icon);
            return Files.isRegularFile(path) && !icon.endsWith(".svg") ? path : null;
        }
        for (Path dataDir : dataDirs()) {
            for (String size : ICON_SIZES) {
                Path png = dataDir.resolve("icons/hicolor/" + size + "/apps/" + icon + ".png");
                if (Files.isRegularFile(png)) return png;
            }
            Path pixmap = dataDir.resolve("pixmaps/" + icon + ".png");
            if (Files.isRegularFile(pixmap)) return pixmap;
        }
        return null;
    }

    /** The launcher target an {@code Exec} line starts, or empty. Package-private and pure for the tests. */
    static Optional<LaunchSpec> shortcutIn(String exec) {
        if (exec == null || exec.isBlank()) return Optional.empty();
        Matcher m;
        if ((m = STEAM.matcher(exec)).find()) return Optional.of(new LaunchSpec(LaunchKind.STEAM, m.group(1)));
        if ((m = FAUGUS.matcher(exec)).find()) return Optional.of(new LaunchSpec(LaunchKind.FAUGUS, m.group(1)));
        if ((m = LUTRIS.matcher(exec)).find()) return Optional.of(new LaunchSpec(LaunchKind.LUTRIS, m.group(1)));
        if ((m = HEROIC_QUERY.matcher(exec)).find() || (m = HEROIC_PATH.matcher(exec)).find()) {
            return Optional.of(new LaunchSpec(LaunchKind.HEROIC, m.group(1)));
        }
        if ((m = WAYDROID.matcher(exec)).find()) {
            return Optional.of(new LaunchSpec(LaunchKind.EMULATOR_APP,
                    m.group(1) + "@" + WaydroidPlatform.INSTANCE_NAME));
        }
        return Optional.empty();
    }

    /**
     * The {@code [Desktop Entry]} group of a file as an entry, or empty when the menu would not show it.
     * Package-private and pure for the tests.
     */
    static Optional<Entry> parse(String id, String text) {
        if (text == null) return Optional.empty();
        Map<String, String> keys = new LinkedHashMap<>();
        boolean inEntry = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                inEntry = line.equals("[Desktop Entry]");
                continue;
            }
            int eq = line.indexOf('=');
            if (!inEntry || eq <= 0 || line.startsWith("#")) continue;
            // Name[fr]=… is a translation; the untranslated key is the one every desktop falls back to.
            keys.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        if (!"Application".equals(keys.get("Type"))
                || "true".equalsIgnoreCase(keys.get("NoDisplay"))
                || "true".equalsIgnoreCase(keys.get("Hidden"))) {
            return Optional.empty();
        }
        String name = keys.getOrDefault("Name", "");
        String exec = keys.getOrDefault("Exec", "");
        if (name.isBlank() || exec.isBlank()) return Optional.empty();
        Set<String> categories = new LinkedHashSet<>();
        for (String category : keys.getOrDefault("Categories", "").split(";")) {
            if (!category.isBlank()) categories.add(category.strip());
        }
        return Optional.of(new Entry(id, name, exec, keys.getOrDefault("Icon", ""), Set.copyOf(categories),
                keys.getOrDefault("X-Flatpak", "")));
    }

    /**
     * The file name of the program an {@code Exec} line runs, past an {@code env VAR=value} prefix and quotes.
     * Package-private and pure for the tests.
     */
    static String programOf(String exec) {
        if (exec == null) return "";
        String rest = exec.strip();
        if (rest.startsWith("env ")) {
            rest = rest.substring(4).strip();
            while (!rest.isEmpty() && !rest.startsWith("\"") && rest.split("\\s+", 2)[0].contains("=")) {
                String[] split = rest.split("\\s+", 2);
                rest = split.length < 2 ? "" : split[1].strip();
            }
        }
        if (rest.isEmpty()) return "";
        String program;
        if (rest.startsWith("\"") || rest.startsWith("'")) {
            int close = rest.indexOf(rest.charAt(0), 1);
            program = close < 0 ? rest.substring(1) : rest.substring(1, close);
        } else {
            program = rest.split("\\s+", 2)[0];
        }
        int slash = program.lastIndexOf('/');
        return slash >= 0 ? program.substring(slash + 1) : program;
    }

    /** {@code $XDG_DATA_HOME} then {@code $XDG_DATA_DIRS}, with the Flatpak export dirs a bare session lacks. */
    private static List<Path> dataDirs() {
        String home = System.getProperty("user.home", "");
        Set<Path> dirs = new LinkedHashSet<>();
        String dataHome = System.getenv("XDG_DATA_HOME");
        dirs.add(dataHome == null || dataHome.isBlank() ? Path.of(home, ".local", "share") : Path.of(dataHome));
        String dataDirs = System.getenv("XDG_DATA_DIRS");
        for (String dir : (dataDirs == null || dataDirs.isBlank() ? "/usr/local/share:/usr/share" : dataDirs)
                .split(":")) {
            if (!dir.isBlank()) dirs.add(Path.of(dir));
        }
        dirs.add(Path.of(home, ".local", "share", "flatpak", "exports", "share"));
        dirs.add(Path.of("/var/lib/flatpak/exports/share"));
        return List.copyOf(dirs);
    }

    private static String idOf(Path applications, Path file) {
        String relative = applications.relativize(file).toString().replace('/', '-');
        return relative.substring(0, relative.length() - ".desktop".length());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
