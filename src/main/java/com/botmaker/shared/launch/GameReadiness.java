package com.botmaker.shared.launch;

import com.botmaker.shared.game.SteamLibraryScanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a launch target's game can start now, read off its launcher's files on this computer: installed, an
 * update pending or running, the launcher signed out. Steam is read whole ({@code appmanifest_<id>.acf}'s
 * {@code StateFlags}, {@code config/loginusers.vdf}); a Heroic game is checked installed; every other kind is
 * {@link State#UNKNOWN}, which launches as before. Reads no network and no password; never throws.
 *
 * @param state  what the launcher's files say
 * @param reason the sentence a user reads, naming the game and the launcher; empty when {@link State#READY}
 */
public record GameReadiness(State state, String reason) {

    /** What a launcher's files say of a game. */
    public enum State {
        READY("ready"),
        /** An update is due and nothing downloads it yet: the launcher fetches it when the game is started. */
        UPDATE_PENDING("update pending"),
        /** The launcher is downloading or installing it now. */
        UPDATING("updating"),
        SIGNED_OUT("signed out"),
        NOT_INSTALLED("not installed"),
        /** Nothing read for this kind: launch it as before. */
        UNKNOWN("unknown");

        private final String displayName;

        State(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** Whether a launch goes ahead in this state, at once. */
        public boolean launchable() {
            return this == READY || this == UPDATE_PENDING || this == UNKNOWN;
        }
    }

    /** How long a run waits on a launcher's download before it gives up. */
    public static final Duration UPDATE_WAIT = Duration.ofMinutes(30);

    private static final Duration POLL = Duration.ofSeconds(5);

    /** How often a wait says it is still waiting. */
    private static final Duration WAIT_NOTE = Duration.ofMinutes(1);

    private static final Pattern STATE_FLAGS = Pattern.compile("\"StateFlags\"\\s+\"(\\d+)\"");
    private static final Pattern TITLE = Pattern.compile("\"name\"\\s+\"([^\"]*)\"");
    private static final Pattern REMEMBERED =
            Pattern.compile("\"(RememberPassword|AutoLogin|AllowAutoLogin)\"\\s+\"1\"");

    // Steam's EAppState bits (appmanifest StateFlags).
    private static final long UPDATE_REQUIRED = 2, FULLY_INSTALLED = 4, FILES_MISSING = 32, FILES_CORRUPT = 128,
            UPDATE_RUNNING = 256, UPDATE_PAUSED = 512, UPDATE_STARTED = 1024, UNINSTALLING = 2048,
            RECONFIGURING = 65536, VALIDATING = 131072, ADDING_FILES = 262144, PREALLOCATING = 524288,
            DOWNLOADING = 1048576, STAGING = 2097152, COMMITTING = 4194304;
    private static final long ACTIVE = UPDATE_RUNNING | UPDATE_STARTED | RECONFIGURING | VALIDATING | ADDING_FILES
            | PREALLOCATING | DOWNLOADING | STAGING | COMMITTING;
    private static final long PENDING = UPDATE_REQUIRED | UPDATE_PAUSED | FILES_MISSING | FILES_CORRUPT;

    public static GameReadiness ready() {
        return new GameReadiness(State.READY, "");
    }

    private static GameReadiness unknown() {
        return new GameReadiness(State.UNKNOWN, "");
    }

    /** What this computer's launcher files say of {@code spec}'s game. Blocking (reads a few files). */
    public static GameReadiness of(LaunchSpec spec) {
        try {
            if (spec == null) return unknown();
            return switch (spec.kind()) {
                case STEAM -> steam(spec, SteamLibraryScanner.root(), HostLauncherProbe.isRunning(LaunchKind.STEAM));
                case HEROIC -> heroic(spec);
                default -> unknown();
            };
        } catch (RuntimeException e) {
            return unknown();
        }
    }

    /**
     * {@link #of} for a Steam game under {@code root}, the Steam install ({@code null}: none found where we look,
     * which says nothing: a launch goes through Steam's link anyway). {@code running}: whether Steam's client runs
     * now. A running client is signed in, remembered or not, and the only one that downloads.
     */
    static GameReadiness steam(LaunchSpec spec, Path root, boolean running) {
        String name = spec.describe();
        if (root == null) return unknown();
        if (!running && !signedIn(read(root.resolve("config/loginusers.vdf")))) {
            return new GameReadiness(State.SIGNED_OUT, "Steam is signed out on this computer: sign in to Steam, "
                    + "and tick Remember me, so it can start " + name + ".");
        }
        // A shortcut has no manifest: the program it starts is the user's, and Steam's own files say nothing of it.
        if (SteamIds.isShortcut(spec.token())) return ready();
        Optional<Path> manifest = SteamLibraryScanner.manifest(root, spec.token());
        if (manifest.isEmpty()) {
            return new GameReadiness(State.NOT_INSTALLED, name + " isn't installed in Steam on this computer.");
        }
        String text = read(manifest.get());
        Matcher title = text == null ? null : TITLE.matcher(text);
        return fromFlags(title != null && title.find() && !title.group(1).isBlank() ? title.group(1) : name,
                flags(text), running);
    }

    /**
     * A Steam game's state by its manifest's {@code StateFlags} ({@code -1}: unreadable). A download counts as
     * running only while Steam's client does and it isn't paused: a client closed mid-download leaves its flags set,
     * and starting the game is what resumes it. Pure, for the tests.
     */
    static GameReadiness fromFlags(String name, long flags, boolean steamRunning) {
        if (flags < 0) return unknown();
        if (steamRunning && (flags & ACTIVE) != 0 && (flags & UPDATE_PAUSED) == 0) {
            return new GameReadiness(State.UPDATING, "Steam is downloading " + name + ".");
        }
        if ((flags & UNINSTALLING) != 0 || (flags & FULLY_INSTALLED) == 0) {
            return new GameReadiness(State.NOT_INSTALLED, name + " isn't fully installed in Steam on this computer.");
        }
        if ((flags & (PENDING | ACTIVE)) != 0) {
            return new GameReadiness(State.UPDATE_PENDING, "Steam has an update for " + name
                    + ": it downloads it before the game starts, which can take a while.");
        }
        return ready();
    }

    /** A manifest's {@code StateFlags}, or {@code -1}. Pure, for the tests. */
    static long flags(String manifest) {
        Matcher m = manifest == null ? null : STATE_FLAGS.matcher(manifest);
        try {
            return m != null && m.find() ? Long.parseLong(m.group(1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether {@code loginusers.vdf} names an account Steam signs in by itself: one whose password it remembers.
     * Signing out clears it, and an account that never ticked Remember me is asked for a password each start,
     * which no run can type. Pure, for the tests.
     */
    static boolean signedIn(String loginUsers) {
        return loginUsers != null && REMEMBERED.matcher(loginUsers).find();
    }

    private static GameReadiness heroic(LaunchSpec spec) {
        // No Heroic config read means nothing known, not missing: Heroic may keep it where we don't look.
        if (HeroicLibrary.configRoots().isEmpty() || HeroicLibrary.games().isEmpty()) return unknown();
        return HeroicLibrary.find(spec.token()) != null ? ready()
                : new GameReadiness(State.NOT_INSTALLED, spec.describe() + " isn't installed in Heroic on this computer.");
    }

    /**
     * Waits for {@code spec}'s game to be launchable: returns at once when it is, polls while its launcher downloads
     * it (each minute telling {@code progress}), and throws when it can't start.
     *
     * @throws IllegalStateException when the game isn't installed, the launcher is signed out, or the download
     *                               outlasts {@code timeout}; the message is {@link #reason()}
     */
    public static void awaitLaunchable(LaunchSpec spec, Duration timeout, Consumer<String> progress) {
        awaitLaunchable(() -> of(spec), timeout, POLL, progress);
    }

    /** {@link #awaitLaunchable(LaunchSpec, Duration, Consumer)} over {@code probe}. Package-private for the tests. */
    static void awaitLaunchable(Supplier<GameReadiness> probe, Duration timeout, Duration poll,
                                Consumer<String> progress) {
        long start = System.nanoTime();
        long noted = -1;
        while (true) {
            GameReadiness now = probe.get();
            if (now.state() == State.UPDATE_PENDING) progress.accept(now.reason());
            if (now.state().launchable()) return;
            if (now.state() != State.UPDATING) throw new IllegalStateException(now.reason());
            long waited = System.nanoTime() - start;
            if (waited >= timeout.toNanos()) {
                throw new IllegalStateException(now.reason() + " Still downloading after " + timeout.toMinutes()
                        + " min: launch it once it's done.");
            }
            long minute = waited / Math.max(1, WAIT_NOTE.toNanos());
            if (minute != noted) {
                noted = minute;
                progress.accept(now.reason() + " Waiting for it to finish…");
            }
            try {
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Stopped waiting for " + now.reason(), e);
            }
        }
    }

    /**
     * The link that has the launcher install {@code spec}'s game, or empty for a kind with none: Steam's
     * {@code steam://install/<id>}, which opens Steam's install dialog. Pure.
     */
    public static Optional<String> installLink(LaunchSpec spec) {
        return spec != null && spec.kind() == LaunchKind.STEAM && !SteamIds.isShortcut(spec.token())
                && spec.token().matches("\\d+") ? Optional.of("steam://install/" + spec.token()) : Optional.empty();
    }

    /** The link that brings the launcher's own window up, to sign in: Steam's main window. Pure. */
    public static Optional<String> signInLink(LaunchKind kind) {
        return kind == LaunchKind.STEAM ? Optional.of("steam://open/main") : Optional.empty();
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
