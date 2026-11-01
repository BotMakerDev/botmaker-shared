package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Installs and updates a launch target's game, whatever launcher it comes from (2026-11-01, the user: "Botmaker
 * should be able to handle installation/update of a game for any platform"). One implementation per kind that has
 * a way in: an Android app ({@link AndroidInstall}: the APK library, Google Play's files through goopdl, the Play app,
 * apkeep, a store page), Steam, Lutris
 * and Heroic ({@link HeroicInstall}); every other kind says it can't. Studio's game dialog and the assistant's
 * {@code setup} actions both go through here.
 *
 * <p>A launcher that needs the user — Steam's install dialog, Lutris's installer, Google Play's Install button —
 * is opened on the game and the answer is {@link Outcome#WAITING_FOR_USER}, with what to press. Nothing is guessed:
 * a launcher with no update command says so.
 *
 * <p>{@link #install} and {@link #update} block (a download takes minutes) and are interruptible; run them off a
 * UI thread.
 */
public sealed interface GameInstall permits AndroidInstall, HeroicInstall, GameInstall.Steam, GameInstall.Lutris,
        GameInstall.None {

    /** What an install or update ended in. */
    enum Outcome {
        INSTALLED("installed"),
        UPDATED("updated"),
        UP_TO_DATE("up to date"),
        /** The launcher is open on the game and the user finishes there; the message says what to press. */
        WAITING_FOR_USER("waiting for you"),
        /** There is no way in from here: no store on the device, no launcher command. */
        NO_STORE("no store"),
        FAILED("failed");

        private final String displayName;

        Outcome(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** Whether the game is there, as new as BotMaker can make it. */
        public boolean done() {
            return this == INSTALLED || this == UPDATED || this == UP_TO_DATE;
        }
    }

    /**
     * What an install or update did.
     *
     * @param outcome how it ended
     * @param message a sentence a UI or the assistant shows as it is
     */
    record Result(Outcome outcome, String message) {}

    /** The target this installs. */
    LaunchSpec spec();

    /** Whether the game can start now, as {@link GameReadiness#of} reads it. Blocking (reads a few files). */
    default GameReadiness readiness() {
        return GameReadiness.of(spec());
    }

    /** Installs the game; a game already there is {@link Outcome#UP_TO_DATE}. */
    Result install(Consumer<String> progress);

    /** Brings the game up to date; a game not installed yet is installed. */
    Result update(Consumer<String> progress);

    /** Where the game comes from, a phrase for the user ("Steam", "Google Play (goopdl or the Play app) or a store page"). */
    String source();

    /**
     * The install for {@code spec}.
     *
     * @param apkPureAcknowledged whether the user accepted downloading from APKPure ({@code ApkDownload.DANGERS}):
     *                            only an Android app reads it
     */
    static GameInstall of(LaunchSpec spec, boolean apkPureAcknowledged) {
        if (spec == null) return new None(LaunchSpec.parse(""));
        return switch (spec.kind()) {
            case EMULATOR_APP -> new AndroidInstall(spec, apkPureAcknowledged);
            case STEAM -> new Steam(spec);
            case LUTRIS -> new Lutris(spec);
            case HEROIC -> new HeroicInstall(spec);
            default -> new None(spec);
        };
    }

    /** How long an install waits for a launcher to show it started, before handing over to the user. */
    Duration HANDOVER = Duration.ofMinutes(2);

    /**
     * Polls {@code readiness} until it leaves {@link GameReadiness.State#NOT_INSTALLED} or {@code timeout} passes;
     * returns the last answer. Interrupted: the last answer at once.
     */
    static GameReadiness awaitStarted(Supplier<GameReadiness> readiness, Duration timeout, Duration poll) {
        long deadline = System.nanoTime() + timeout.toNanos();
        GameReadiness now = readiness.get();
        while (now.state() == GameReadiness.State.NOT_INSTALLED && System.nanoTime() < deadline) {
            try {
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return now;
            }
            now = readiness.get();
        }
        return now;
    }

    /**
     * A Steam game: Steam's own install dialog ({@code steam://install/<id>}), which the user confirms, and Steam's
     * validate ({@code steam://validate/<id>}) for an update Steam hasn't started, which downloads what is missing or
     * stale. Steam updates a game itself; BotMaker only starts it.
     */
    record Steam(LaunchSpec spec) implements GameInstall {

        @Override
        public Result install(Consumer<String> progress) {
            GameReadiness now = readiness();
            if (now.state() == GameReadiness.State.SIGNED_OUT) return new Result(Outcome.FAILED, now.reason());
            if (now.state() != GameReadiness.State.NOT_INSTALLED && now.state() != GameReadiness.State.UNKNOWN) {
                return new Result(Outcome.UP_TO_DATE, spec.describe() + " is already installed in Steam.");
            }
            Optional<String> link = GameReadiness.installLink(spec);
            if (link.isEmpty()) {
                return new Result(Outcome.NO_STORE, spec.describe() + " is a shortcut Steam can't install: install "
                        + "the program it starts yourself.");
            }
            if (!UriLauncher.open(link.get())) {
                return new Result(Outcome.FAILED, "Steam didn't open: is it installed?");
            }
            report(progress, "Steam's install dialog is open for " + spec.describe() + ": press Install there.");
            GameReadiness after = awaitStarted(this::readiness, HANDOVER, Duration.ofSeconds(3));
            return switch (after.state()) {
                case READY -> new Result(Outcome.INSTALLED, spec.describe() + " is installed in Steam.");
                case UPDATING, UPDATE_PENDING -> new Result(Outcome.WAITING_FOR_USER, "Steam is downloading "
                        + spec.describe() + ". It finishes on its own; a launch waits for it.");
                default -> new Result(Outcome.WAITING_FOR_USER, "Steam's install dialog is open for "
                        + spec.describe() + ": press Install there.");
            };
        }

        @Override
        public Result update(Consumer<String> progress) {
            GameReadiness now = readiness();
            return switch (now.state()) {
                case NOT_INSTALLED, UNKNOWN -> install(progress);
                case SIGNED_OUT -> new Result(Outcome.FAILED, now.reason());
                // Steam's files say no update is pending; Steam itself finds a new one when it next checks.
                case READY -> new Result(Outcome.UP_TO_DATE, "Steam has no update pending for " + spec.describe()
                        + ".");
                case UPDATING -> new Result(Outcome.WAITING_FOR_USER, now.reason() + " It finishes on its own.");
                case UPDATE_PENDING -> UriLauncher.open("steam://validate/" + spec.token())
                        ? new Result(Outcome.WAITING_FOR_USER, "Steam is fetching " + spec.describe()
                        + "'s update. It finishes on its own; a launch waits for it.")
                        : new Result(Outcome.FAILED, "Steam didn't open: is it installed?");
            };
        }

        @Override
        public String source() {
            return "Steam";
        }
    }

    /**
     * A Lutris game: Lutris's own installer ({@code lutris:install/<slug>}, or {@code lutris:rungameid/<id>} for a
     * library game not installed), which the user goes through. Lutris has no update command: a Lutris game updates
     * itself or through the launcher it runs, so {@link #update} says that rather than guess.
     */
    record Lutris(LaunchSpec spec) implements GameInstall {

        @Override
        public Result install(Consumer<String> progress) {
            if (readiness().state() == GameReadiness.State.READY) {
                return new Result(Outcome.UP_TO_DATE, spec.describe() + " is already installed in Lutris.");
            }
            if (!GameLauncher.openLutrisInstaller(spec.token())) {
                return new Result(Outcome.NO_STORE, "Lutris isn't installed on this computer.");
            }
            LutrisLibrary.invalidate();
            return new Result(Outcome.WAITING_FOR_USER, "Lutris's installer is open for " + spec.describe()
                    + ": go through it there.");
        }

        @Override
        public Result update(Consumer<String> progress) {
            if (readiness().state() == GameReadiness.State.NOT_INSTALLED) return install(progress);
            LutrisLibrary.Game game = LutrisLibrary.find(spec.token());
            String how = game != null && game.throughSteam() ? "Steam updates it, as it does every Steam game."
                    : "a game updates itself, or through the launcher Lutris runs.";
            return new Result(Outcome.FAILED, "Lutris has no update for its games: " + how);
        }

        @Override
        public String source() {
            return "Lutris";
        }
    }

    /** A kind BotMaker has no way to install: a program, a link, a desktop app. */
    record None(LaunchSpec spec) implements GameInstall {

        @Override
        public Result install(Consumer<String> progress) {
            return new Result(Outcome.NO_STORE, "BotMaker can't install " + spec.describe() + ": install it yourself.");
        }

        @Override
        public Result update(Consumer<String> progress) {
            return new Result(Outcome.NO_STORE, "BotMaker can't update " + spec.describe() + ": update it yourself.");
        }

        @Override
        public String source() {
            return "yourself";
        }
    }

    private static void report(Consumer<String> progress, String message) {
        Diag.log("[Install] " + message);
        if (progress != null) progress.accept(message);
    }
}
