package com.botmaker.shared.launch;

import com.botmaker.shared.Spawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The macOS counterpart of the desktop's application menu: the {@code .app} bundles in {@code /Applications} and
 * {@code ~/Applications}, one folder deep ({@code /Applications/Utilities}, a vendor's folder). A GOG game, a
 * native Mac game and any other app all install there, so this is how they reach the picker.
 *
 * <p>Each bundle becomes a {@link DesktopEntries.Entry}, so {@code desktop:} targets mean the same thing on both
 * systems: the id is the bundle's path, which {@code open -a} takes; the {@code Exec} is the bundle's own
 * executable, whose whole path is what a running copy shows in the process table (its file name alone is
 * usually the app's name, "Chess", which any command line or window may mention); the {@code Game} category comes
 * from {@code LSApplicationCategoryType}. No icon: a bundle's is an {@code .icns}, which JavaFX can't draw.
 */
final class MacApps {

    private MacApps() {}

    /** How long {@code plutil} may take to turn one binary {@code Info.plist} into XML. */
    private static final Duration PLUTIL_TIMEOUT = Duration.ofSeconds(5);

    /**
     * A nested {@code <dict>}/{@code <array>} opening or closing, or a {@code <key>K</key>} followed by its
     * {@code <string>V</string>}: enough to keep only the top-level strings, the only values read here.
     */
    private static final Pattern PLIST_TOKEN =
            Pattern.compile("<(/?)(dict|array)>|<key>([^<]+)</key>\\s*<string>([^<]*)</string>");

    /**
     * The folders searched for bundles: the user's, then Apple's own apps, which live in
     * {@code /System/Applications} since macOS 10.15.
     */
    static List<Path> roots(String home) {
        return List.of(Path.of("/Applications"), Path.of(home, "Applications"), Path.of("/System/Applications"));
    }

    /** Every app in {@link #roots}, as menu entries. Reads files, and may run {@code plutil}: not on a UI thread. */
    static List<DesktopEntries.Entry> list() {
        List<DesktopEntries.Entry> entries = new ArrayList<>();
        for (Path root : roots(System.getProperty("user.home", ""))) {
            for (Path app : bundles(root)) entry(app).ifPresent(entries::add);
        }
        return List.copyOf(entries);
    }

    /** The entry for the bundle at {@code app}, read from its own {@code Info.plist}, or empty. */
    static Optional<DesktopEntries.Entry> entry(Path app) {
        return isBundle(app) ? entry(app, MacApps::infoPlist) : Optional.empty();
    }

    /**
     * What to spawn for {@code path}: an {@code .app} bundle's own executable, since a bundle is a folder and a
     * folder does not run; otherwise {@code path} itself. A trailing slash (Finder's spelling of a bundle) is
     * allowed.
     */
    static String program(String path) {
        String bundle = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (!bundle.endsWith(".app")) return path;
        return executable(Path.of(bundle)).map(Object::toString).orElse(path);
    }

    /** The {@code .app} bundles directly in {@code root} and in its subfolders that are not bundles themselves. */
    static List<Path> bundles(Path root) {
        List<Path> apps = new ArrayList<>();
        if (!Files.isDirectory(root)) return apps;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.sorted().toList()) {
                if (isBundle(child)) {
                    apps.add(child);
                } else if (Files.isDirectory(child)) {
                    try (Stream<Path> nested = Files.list(child)) {
                        nested.filter(MacApps::isBundle).sorted().forEach(apps::add);
                    } catch (IOException | RuntimeException unreadable) {
                        // one unreadable folder hides its apps, not the rest
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // an unreadable root lists nothing
        }
        return apps;
    }

    /**
     * The entry for {@code app}, or empty when it has no executable to run. {@code plist} reads the bundle's
     * {@code Info.plist} as XML text (or null). Package-private and pure but for the files, for the tests.
     */
    static Optional<DesktopEntries.Entry> entry(Path app, Function<Path, String> plist) {
        Map<String, String> info = strings(plist.apply(app.resolve("Contents/Info.plist")));
        Optional<Path> executable = executable(app, info);
        if (executable.isEmpty()) return Optional.empty();
        String name = firstNonBlank(info.get("CFBundleDisplayName"), info.get("CFBundleName"), bundleName(app));
        String category = info.getOrDefault("LSApplicationCategoryType", "");
        // public.app-category.games and each of its sub-genres (…action-games, …puzzle-games).
        Set<String> categories = category.toLowerCase(Locale.ROOT).contains("games") ? Set.of("Game") : Set.of();
        return Optional.of(new DesktopEntries.Entry(app.toString(), name, "\"" + executable.get() + "\"", "",
                categories, ""));
    }

    /**
     * The program inside {@code app}: {@code Contents/MacOS/<CFBundleExecutable>}, else the one named after the
     * bundle, else the only file there. Empty for a bundle with none.
     */
    static Optional<Path> executable(Path app) {
        return executable(app, strings(infoPlist(app.resolve("Contents/Info.plist"))));
    }

    private static Optional<Path> executable(Path app, Map<String, String> info) {
        Path macos = app.resolve("Contents/MacOS");
        for (String name : new String[] {info.get("CFBundleExecutable"), bundleName(app)}) {
            if (name == null || name.isBlank()) continue;
            Path candidate = macos.resolve(name);
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        try (Stream<Path> files = Files.list(macos)) {
            List<Path> all = files.filter(Files::isRegularFile).limit(2).toList();
            return all.size() == 1 ? Optional.of(all.get(0)) : Optional.empty();
        } catch (IOException | RuntimeException none) {
            return Optional.empty();
        }
    }

    /**
     * The top-level string values of an XML property list: those of the root {@code <dict>}, not of a dict or
     * array nested in it ({@code CFBundleDocumentTypes}, {@code CFBundleURLTypes}). Package-private and pure for
     * the tests.
     */
    static Map<String, String> strings(String xml) {
        Map<String, String> values = new LinkedHashMap<>();
        if (xml == null) return values;
        Matcher m = PLIST_TOKEN.matcher(xml);
        int depth = 0;
        while (m.find()) {
            if (m.group(2) != null) {
                depth += m.group(1).isEmpty() ? 1 : -1;
            } else if (depth == 1) {
                values.putIfAbsent(m.group(3).strip(), unescape(m.group(4).strip()));
            }
        }
        return values;
    }

    /**
     * An {@code Info.plist} as XML text, or null. Most bundles ship XML; a binary one ({@code bplist00}) goes
     * through {@code plutil}, which every Mac has.
     */
    private static String infoPlist(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (!new String(bytes, 0, Math.min(6, bytes.length), StandardCharsets.US_ASCII).equals("bplist")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            Spawn.Completed xml = Spawn.run(PLUTIL_TIMEOUT, "plutil", "-convert", "xml1", "-o", "-", file.toString());
            return xml == null ? null : xml.output();
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static String bundleName(Path app) {
        String file = app.getFileName().toString();
        return file.substring(0, file.length() - ".app".length());
    }

    private static boolean isBundle(Path path) {
        return path.getFileName().toString().endsWith(".app") && Files.isDirectory(path);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
