package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.Executables;
import com.botmaker.shared.Spawn;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A Heroic game ({@code heroic:<appName>}). An Epic game is installed and updated by {@code legendary}, the CLI
 * Heroic itself bundles and drives, over Heroic's own {@code legendaryConfig}: what it installs Heroic lists as its
 * own. A GOG or Amazon game is handed to Heroic's window: its {@code gogdl}/{@code nile} write no record Heroic
 * reads, so a game installed behind its back would not show there.
 *
 * <p>The store is told by the id: GOG's are numbers, Amazon's {@code amzn1.…}, Epic's anything else.
 */
public record HeroicInstall(LaunchSpec spec) implements GameInstall {

    /** Where Heroic installs a game unless told otherwise, and so where this does. */
    private static final String DEFAULT_BASE = "Games/Heroic";
    /** A game is gigabytes; past this the download is left to Heroic. */
    private static final Duration TIMEOUT = Duration.ofHours(6);

    /** The store behind a Heroic game. */
    enum Store {
        EPIC("Epic"), GOG("GOG"), AMAZON("Amazon");

        private final String displayName;

        Store(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }

        /** The store of a Heroic app name. Pure. */
        static Store of(String appName) {
            String id = appName == null ? "" : appName.trim();
            if (id.matches("\\d+")) return GOG;
            if (id.startsWith("amzn1.")) return AMAZON;
            return EPIC;
        }
    }

    @Override
    public Result install(Consumer<String> progress) {
        if (readiness().state() == GameReadiness.State.READY) {
            return new Result(Outcome.UP_TO_DATE, spec.describe() + " is already installed in Heroic.");
        }
        return run(progress, false);
    }

    @Override
    public Result update(Consumer<String> progress) {
        if (readiness().state() == GameReadiness.State.NOT_INSTALLED) return run(progress, false);
        return run(progress, true);
    }

    @Override
    public String source() {
        return "Heroic (" + Store.of(spec.token()).displayName() + ")";
    }

    private Result run(Consumer<String> progress, boolean update) {
        String appName = spec.token().trim();
        Store store = Store.of(appName);
        Optional<Path> root = HeroicLibrary.configRoots().stream().findFirst();
        Optional<Path> legendary = cli("legendary");
        if (store != Store.EPIC || root.isEmpty() || legendary.isEmpty()) return handToHeroic(store);
        List<String> command = legendary(legendary.get(), appName, update,
                Path.of(System.getProperty("user.home", ""), DEFAULT_BASE));
        report(progress, (update ? "Updating " : "Installing ") + spec.describe() + " through Heroic's legendary…");
        AtomicBoolean current = new AtomicBoolean();
        Consumer<String> lines = line -> {
            if (alreadyCurrent(line)) current.set(true);
            if (progress != null) progress.accept(line);
        };
        try {
            Optional<String> failure = runLogged(command,
                    Map.of("LEGENDARY_CONFIG_PATH", root.get().resolve("legendaryConfig/legendary").toString()),
                    lines);
            HeroicLibrary.invalidate();
            if (failure.isPresent()) {
                return new Result(Outcome.FAILED, "legendary couldn't " + (update ? "update " : "install ")
                        + spec.describe() + ": " + failure.get() + signInHint(failure.get()));
            }
            if (update && current.get()) {
                return new Result(Outcome.UP_TO_DATE, spec.describe() + " is up to date.");
            }
            if (update) {
                return new Result(Outcome.UPDATED, spec.describe() + " is updated. Restart Heroic if it still "
                        + "shows an update.");
            }
            return new Result(Outcome.INSTALLED, spec.describe() + " is installed. Restart Heroic to see it there.");
        } catch (IOException e) {
            return new Result(Outcome.FAILED, "legendary didn't start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, "Stopped " + (update ? "updating " : "installing ") + spec.describe()
                    + "; legendary resumes it next time.");
        }
    }

    /** Opens Heroic on the game, where the user installs or updates it. */
    private Result handToHeroic(Store store) {
        String link = "heroic://launch/" + spec.token().trim();
        if (!UriLauncher.open(link)) {
            return new Result(Outcome.NO_STORE, "Heroic isn't installed on this computer.");
        }
        return new Result(Outcome.WAITING_FOR_USER, "Heroic is open on " + spec.describe() + " (" + store.displayName()
                + "): install or update it there.");
    }

    /**
     * legendary's command line: {@code install <app> -y --base-path <base>}, or {@code update <app> -y}. Pure, for
     * the tests.
     */
    static List<String> legendary(Path binary, String appName, boolean update, Path base) {
        return update ? List.of(binary.toString(), "update", appName, "-y")
                : List.of(binary.toString(), "install", appName, "-y", "--base-path", base.toString());
    }

    /**
     * Heroic's bundled {@code name} ({@code legendary}, {@code gogdl}, {@code nile}): its Flatpak (system then user),
     * its native packages, then one on {@code PATH}. The first that is there.
     */
    static Optional<Path> cli(String name) {
        String home = System.getProperty("user.home", "");
        for (Path candidate : cliCandidates(home, name)) {
            if (Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Executables.find(name).map(File::toPath);
    }

    /** Where Heroic keeps its bundled CLIs, in the order {@link #cli} looks. Pure, for the tests. */
    static List<Path> cliCandidates(String home, String name) {
        String bundled = "resources/app.asar.unpacked/build/bin/x64/linux/" + name;
        String flatpak = "app/com.heroicgameslauncher.hgl/current/active/files/bin/heroic/" + bundled;
        return List.of(Path.of("/var/lib/flatpak", flatpak), Path.of(home, ".local/share/flatpak", flatpak),
                Path.of("/opt/Heroic", bundled), Path.of("/usr/lib/heroic", bundled),
                Path.of("/opt/heroic", bundled));
    }

    /** Whether a legendary line says there was nothing to update ("… is already up to date"). Pure. */
    static boolean alreadyCurrent(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("up to date") || lower.contains("up-to-date") || lower.contains("no update");
    }

    /** A hint added to a failure that reads like a sign-in problem. */
    private static String signInHint(String failure) {
        String lower = failure.toLowerCase(Locale.ROOT);
        return lower.contains("credential") || lower.contains("login") || lower.contains("log in")
                ? " Sign in to Epic in Heroic first." : "";
    }

    /** Runs {@code command}, passing each line on; returns its last line when it failed. */
    private static Optional<String> runLogged(List<String> command, Map<String, String> env,
                                              Consumer<String> progress) throws IOException, InterruptedException {
        Diag.log("[Install] " + String.join(" ", command));
        Spawn.Completed done = Spawn.streamed(TIMEOUT, command, env, progress);
        if (done == null) return Optional.of("still running after " + TIMEOUT.toHours() + " hours");
        if (done.ok()) return Optional.empty();
        return Optional.of(done.output().isBlank() ? "exit " + done.exitCode() : done.output());
    }

    private static void report(Consumer<String> progress, String message) {
        Diag.log("[Install] " + message);
        if (progress != null) progress.accept(message);
    }
}
