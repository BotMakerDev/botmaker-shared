package com.botmaker.shared.emulator;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.tools.UserDirs;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a Windows product is installed, asked the way every product can answer: its own registry key when it
 * writes one, then the entry Windows keeps for it under <i>Apps &amp; features</i>, then the folders it installs
 * to by default.
 *
 * <p>The middle step is the one that matters. Each product's own key changes with its major version
 * ({@code LDPlayer9} → none at all in LDPlayer 14, {@code MuMuPlayer-12.0} → none in MuMu 5) and with its
 * edition ({@code BlueStacks_nxt}, {@code BlueStacks_msi5}), so a detector reading only that key finds nothing on
 * a current install. Every installer, though, registers an uninstall entry, and that entry says where the product
 * is — in {@code InstallLocation} when the installer bothered, and otherwise in the path of its icon or its
 * uninstaller.
 *
 * <p>Never throws; off Windows every answer is empty.
 */
final class InstallLocator {

    private InstallLocator() {}

    /** The three places Windows keeps uninstall entries: per machine (both views) and per user. */
    private static final List<String> UNINSTALL_ROOTS = List.of(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall");

    /** How long a product's console tool may take to list its instances before discovery moves on without it. */
    static final Duration CONSOLE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * One product's <i>Apps &amp; features</i> entry.
     *
     * @param key the entry's registry key name ({@code LDPlayer14}, {@code MuMuPlayerGlobal}, …)
     */
    record UninstallEntry(String key, String displayName, String installLocation, String displayIcon,
                          String uninstallString) {

        /**
         * The folder the product lives in: {@code InstallLocation}, else the folder of its icon, else of its
         * uninstaller; {@code null} when none of them names a path.
         */
        Path folder() {
            if (installLocation != null && !installLocation.isBlank()) {
                return path(unquote(installLocation));
            }
            for (String program : new String[] {displayIcon, uninstallString}) {
                // Cut as text: these are Windows paths, which a Path on another OS reads as one file name.
                String file = programPath(program);
                int slash = file == null ? -1 : Math.max(file.lastIndexOf('\\'), file.lastIndexOf('/'));
                if (slash > 0) return path(file.substring(0, slash));
            }
            return null;
        }
    }

    /** Every uninstall entry whose key name or display name {@code matches}. */
    static List<UninstallEntry> uninstallEntries(Predicate<String> matches) {
        List<UninstallEntry> entries = new ArrayList<>();
        for (UninstallEntry entry : allUninstallEntries()) {
            if (matches.test(entry.key()) || (entry.displayName() != null && matches.test(entry.displayName()))) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * The folders {@code matches}' uninstall entries name, each followed by its parent: an entry that names its
     * icon gives the folder the icon is in, which for MuMu ({@code nx_main\}) or GameLoop ({@code AppMarket\}) is
     * one level inside the install.
     */
    static List<Path> uninstallFolders(Predicate<String> matches) {
        List<Path> folders = new ArrayList<>();
        for (UninstallEntry entry : uninstallEntries(matches)) {
            Path folder = entry.folder();
            if (folder == null) continue;
            folders.add(folder);
            if (folder.getParent() != null) folders.add(folder.getParent());
        }
        return folders;
    }

    /** How long one read of every uninstall entry answers for: one discovery scan asks for it a dozen times. */
    private static final long UNINSTALL_SNAPSHOT_MS = 30_000;

    private static volatile List<UninstallEntry> snapshot;
    private static volatile long snapshotAt;

    /** Every uninstall entry on the machine, read once per {@link #UNINSTALL_SNAPSHOT_MS}. */
    private static List<UninstallEntry> allUninstallEntries() {
        List<UninstallEntry> cached = snapshot;
        if (cached != null && System.currentTimeMillis() - snapshotAt < UNINSTALL_SNAPSHOT_MS) return cached;
        List<UninstallEntry> entries = new ArrayList<>();
        for (String root : UNINSTALL_ROOTS) {
            for (String key : WindowsRegistry.subkeys(root)) {
                Map<String, String> values = WindowsRegistry.values(root + "\\" + key);
                entries.add(new UninstallEntry(key, values.get("DisplayName"), values.get("InstallLocation"),
                        values.get("DisplayIcon"), values.get("UninstallString")));
            }
        }
        snapshot = List.copyOf(entries);
        snapshotAt = System.currentTimeMillis();
        return snapshot;
    }

    /** {@code %ProgramFiles%} joined with {@code more}, or {@code null} when the variable is unset. */
    static Path programFiles(String... more) {
        String programFiles = System.getenv("ProgramFiles");
        return programFiles == null || programFiles.isBlank() ? null : Path.of(programFiles, more);
    }

    /**
     * The existing folders among {@code candidates} (blank or missing ones skipped, each once, in order) that
     * {@code accept} — the test a product uses to say "this is really my install", such as a {@code vms} folder.
     */
    static List<Path> existing(List<Path> candidates, Predicate<Path> accept) {
        Set<Path> found = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            if (candidate == null) continue;
            try {
                Path real = candidate.toAbsolutePath().normalize();
                if (Files.isDirectory(real) && accept.test(real)) found.add(real);
            } catch (RuntimeException e) {
                // an unusable path from the registry is no install
            }
        }
        return List.copyOf(found);
    }

    /** {@code value} as a path, or {@code null} when it is blank or not one. */
    static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The folders directly in {@code parent}, in name order — for products that put each version in its own. */
    static List<Path> children(Path parent) {
        if (parent == null || !Files.isDirectory(parent)) return List.of();
        try (var list = Files.list(parent)) {
            return list.filter(Files::isDirectory).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // A program path as an uninstall entry writes it: quoted, or bare up to its extension, then arguments or ",0".
    private static final Pattern QUOTED = Pattern.compile("^\\s*\"([^\"]+)\"");
    private static final Pattern BARE = Pattern.compile("^\\s*(.+?\\.(?:exe|ico|dll))(?=$|[\\s,])",
            Pattern.CASE_INSENSITIVE);

    /**
     * The file a {@code DisplayIcon} or {@code UninstallString} names, without its quotes, arguments or icon
     * index: {@code "C:\A B\remove.exe" -u} and {@code C:\A B\icon.ico,0} both give the path. {@code null} for blank.
     */
    static String programPath(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher quoted = QUOTED.matcher(value);
        if (quoted.find()) return quoted.group(1).trim();
        Matcher bare = BARE.matcher(value);
        return bare.find() ? bare.group(1).trim() : unquote(value);
    }

    private static String unquote(String value) {
        String v = value.trim();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    /**
     * The instance titles a console tool lists one per line as {@code index,title,…} ({@code ldconsole list2},
     * {@code memuc listvms}), by index — the names the user gave in the product's multi-instance manager, which
     * the config files don't always carry. Empty for {@code null}; a line that isn't one is skipped.
     */
    static Map<Integer, String> titlesByIndex(String output) {
        Map<Integer, String> titles = new LinkedHashMap<>();
        if (output == null) return titles;
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split(",");
            if (fields.length < 2 || fields[1].isBlank()) continue;
            try {
                titles.put(Integer.parseInt(fields[0].trim()), fields[1].trim());
            } catch (NumberFormatException e) {
                // not an instance line
            }
        }
        return titles;
    }

    /**
     * The code page a Windows console tool writes to a pipe in ({@code ldconsole}, {@code memuc}): the system's
     * ANSI one, not UTF-8, so a title such as {@code Café} or {@code 农场} reads back whole.
     */
    static final Charset SYSTEM_CODE_PAGE = systemCodePage();

    private static Charset systemCodePage() {
        try {
            return Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * The instance names a product's console tool reports, by index, as {@code parse} reads its output.
     *
     * <p>Each answer is remembered on disk, and given back when the tool doesn't answer this time (absent,
     * failing, or slower than {@link #CONSOLE_TIMEOUT} while its service starts). A saved reference names an
     * instance by name, so a name must not change between two scans because a tool was slow in one of them.
     * Empty when the tool has never answered; discovery then uses the config files' names.
     */
    static Map<Integer, String> names(Path tool, Charset charset, Function<String, Map<Integer, String>> parse,
                                      String... arguments) {
        if (tool == null) return Map.of();
        Path memory = namesFile(tool, arguments);
        Map<Integer, String> names = parse.apply(console(tool, charset, arguments));
        if (!names.isEmpty()) {
            writeNames(memory, names);
            return names;
        }
        return readNames(memory);
    }

    /** What {@code tool} prints, or {@code null} when it is absent, fails or takes too long. */
    private static String console(Path tool, Charset charset, String... arguments) {
        if (!Files.isRegularFile(tool)) return null;
        List<String> command = new ArrayList<>();
        command.add(tool.toString());
        command.addAll(List.of(arguments));
        try {
            Spawn.Completed done = Spawn.run(CONSOLE_TIMEOUT, charset, command);
            return done == null || !done.ok() ? null : done.output();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Path namesFile(Path tool, String... arguments) {
        String key = (tool + " " + String.join(" ", arguments)).replaceAll("[^A-Za-z0-9._-]", "_");
        return UserDirs.cache().resolve("emulator-names").resolve(key + ".properties");
    }

    private static void writeNames(Path file, Map<Integer, String> names) {
        Properties properties = new Properties();
        names.forEach((index, name) -> properties.setProperty(String.valueOf(index), name));
        try {
            Files.createDirectories(file.getParent());
            Path part = Files.createTempFile(file.getParent(), "names", ".part");
            try {
                try (var out = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
                    properties.store(out, null);
                }
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(part);
            }
        } catch (Exception e) {
            // not remembered: the next slow answer falls back to the config names
        }
    }

    private static Map<Integer, String> readNames(Path file) {
        Map<Integer, String> names = new LinkedHashMap<>();
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(in);
            for (String index : properties.stringPropertyNames()) {
                if (index.matches("\\d+")) names.put(Integer.parseInt(index), properties.getProperty(index));
            }
        } catch (Exception e) {
            // never answered
        }
        return names;
    }
}
