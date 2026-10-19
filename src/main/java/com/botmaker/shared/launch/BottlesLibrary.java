package com.botmaker.shared.launch;

import com.botmaker.shared.platform.Os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The programs <a href="https://usebottles.com/">Bottles</a> lists in its bottles: each bottle's
 * {@code bottle.yml} names its programs under {@code External_Programs}, with the {@code name}
 * {@code bottles-cli run -p} takes and the {@code executable} the running program shows. Read off disk, native
 * install and Flatpak alike; no {@code bottles-cli} is run. Linux only; empty elsewhere. Never throws.
 */
public final class BottlesLibrary {

    /**
     * One program in a bottle.
     *
     * @param bottle     the bottle's name, what {@code bottles-cli run -b} takes
     * @param name       the program's name in Bottles, what {@code -p} takes
     * @param executable the program's file name ({@code Game.exe}), or blank
     */
    public record Program(String bottle, String name, String executable) {

        /** The {@code bottles:} target that starts it. */
        public LaunchSpec spec() {
            return new LaunchSpec(LaunchKind.BOTTLES, bottle + "/" + name);
        }
    }

    private BottlesLibrary() {}

    /** Every program of every bottle, by bottle then name. Reads a few small files. */
    public static List<Program> programs() {
        if (!Os.current().isLinux()) return List.of();
        String home = System.getProperty("user.home", "");
        List<Program> programs = new ArrayList<>();
        for (Path root : List.of(Path.of(home, ".local", "share", "bottles", "bottles"),
                Path.of(home, ".var", "app", LaunchKind.BOTTLES.flatpakAppId(), "data", "bottles", "bottles"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> bottles = Files.list(root)) {
                bottles.sorted().forEach(dir -> {
                    String text = read(dir.resolve("bottle.yml"));
                    if (text != null) programs.addAll(parse(dir.getFileName().toString(), text));
                });
            } catch (IOException | RuntimeException e) {
                // the other install's bottles still count
            }
        }
        return List.copyOf(programs);
    }

    /** The program {@code token} ({@code <bottle>/<program>}) names, or empty. */
    public static Optional<Program> find(String token) {
        LaunchSpec spec = new LaunchSpec(LaunchKind.BOTTLES, token == null ? "" : token.trim());
        if (spec.bottle() == null) return Optional.empty();
        return programs().stream()
                .filter(p -> p.bottle().equals(spec.bottle()) && p.name().equals(spec.bottleProgram()))
                .findFirst();
    }

    /** What a running {@code bottles:<token>} carries: its program's file name, or nothing when it is unknown. */
    public static List<String> runningTokens(String token) {
        return find(token).map(Program::executable).filter(exe -> !exe.isBlank()).map(List::of).orElse(List.of());
    }

    /**
     * The programs a {@code bottle.yml} lists. The bottle is called by its {@code Name}, else its folder's name.
     * Reads only the block-style YAML Bottles writes — top-level keys at the margin, a program's id two spaces in,
     * its fields four — rather than linking a YAML library for three keys. Package-private and pure for the tests.
     */
    static List<Program> parse(String folder, String yaml) {
        String bottle = folder;
        List<String[]> found = new ArrayList<>();
        boolean inPrograms = false;
        String[] current = null;
        for (String line : yaml.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.strip();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).strip();
            String value = unquote(trimmed.substring(colon + 1).strip());
            if (indent == 0) {
                inPrograms = key.equals("External_Programs");
                if (key.equals("Name") && !value.isBlank()) bottle = value;
                current = null;
            } else if (inPrograms && indent == 2) {
                current = new String[2];
                found.add(current);
            } else if (inPrograms && indent == 4 && current != null) {
                if (key.equals("name")) current[0] = value;
                if (key.equals("executable")) current[1] = value;
            }
        }
        List<Program> programs = new ArrayList<>();
        for (String[] p : found) {
            if (p[0] == null || p[0].isBlank()) continue;
            programs.add(new Program(bottle, p[0], p[1] == null ? "" : p[1]));
        }
        programs.sort((a, b) -> a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(programs);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'")
                || value.startsWith("\"") && value.endsWith("\""))) {
            String inner = value.substring(1, value.length() - 1);
            return value.startsWith("'") ? inner.replace("''", "'") : inner.replace("\\\"", "\"");
        }
        return value;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
