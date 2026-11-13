package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.config.ApplyHost;
import com.botmaker.shared.emulator.config.ConfigDiff;
import com.botmaker.shared.emulator.config.GameRecord;
import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.ApkLibrary;
import com.botmaker.shared.launch.AndroidInstall;
import com.botmaker.shared.launch.GameInstall;
import com.botmaker.shared.launch.LaunchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * What Apply does on a running instance past its settings, one instance at a time: the Google accounts its configuration
 * drops are removed first, then those it lacks are signed in, then its missing games are installed. A stopped instance
 * is started for it and stopped again after. Facebook and mail accounts are records only and are left alone.
 * <p>
 * The passwords are the batch's ({@link Passwords}): asked before the instance starts, and an account whose sign-in
 * must not be tried again is dropped from the rest of the batch. No outcome names an email: accounts are counted.
 */
public final class InstanceChanges {

    /** What the changes do to the world; {@link #live()} is this PC's. */
    public interface Host {
        boolean running(EmulatorInstance instance);

        /** Null when it started, else why not. */
        String start(EmulatorInstance instance, Consumer<String> words) throws Exception;

        /** Null when it stopped, else why not. */
        String stop(EmulatorInstance instance) throws Exception;

        AccountDevice device(EmulatorInstance instance) throws Exception;

        GameInstall.Result install(EmulatorInstance instance, GameRecord game, Consumer<String> progress);

        static Host live() {
            return new Host() {
                @Override
                public boolean running(EmulatorInstance instance) {
                    return ApplyHost.live().running(instance);
                }

                @Override
                public String start(EmulatorInstance instance, Consumer<String> words) throws Exception {
                    return ApplyHost.live().start(instance, words);
                }

                @Override
                public String stop(EmulatorInstance instance) throws Exception {
                    return ApplyHost.live().stop(instance);
                }

                @Override
                public AccountDevice device(EmulatorInstance instance) {
                    return AccountDevice.of(instance);
                }

                /** APKPure stays out: its risk is accepted per game, in the Game dialog, not here. */
                @Override
                public GameInstall.Result install(EmulatorInstance instance, GameRecord game,
                                                  Consumer<String> progress) {
                    LaunchSpec spec = LaunchSpec.parse("emu-app:" + game.packageName() + "@" + instance.reference());
                    return new AndroidInstall(spec, false, ApkLibrary.shared()).install(progress);
                }
            };
        }
    }

    private final Host host;
    private final Pace pace;

    public InstanceChanges(Host host) {
        this(host, Pace.REAL);
    }

    InstanceChanges(Host host, Pace pace) {
        this.host = host;
        this.pace = pace;
    }

    /** An account signed in under the address typed and listed by Android under another of its addresses. */
    public interface ListedAs {
        ListedAs NONE = (typed, listed) -> {};

        void listed(String typed, String listed);
    }

    /**
     * Makes {@code instance} hold the accounts and games {@code accounts} and {@code games} say. Null when all of it
     * is done, else what isn't, in the user's words.
     */
    public String run(EmulatorInstance instance, List<ConfigDiff.AccountChange> accounts, List<GameRecord> games,
                      Passwords passwords, Operation.Context context) throws Exception {
        return run(instance, accounts, games, passwords, ListedAs.NONE, context);
    }

    /** As {@link #run(EmulatorInstance, List, List, Passwords, Operation.Context)}, telling {@code listedAs} of each
     * account Android lists under another address, so the configuration can name that one. */
    public String run(EmulatorInstance instance, List<ConfigDiff.AccountChange> accounts, List<GameRecord> games,
                      Passwords passwords, ListedAs listedAs, Operation.Context context) throws Exception {
        List<String> removals = new ArrayList<>();
        List<String> adds = new ArrayList<>();
        for (ConfigDiff.AccountChange change : accounts) {
            if (!change.account().kind().signedInByBotMaker()) continue;
            (change.op() == ConfigDiff.AccountChange.Op.REMOVE ? removals : adds).add(change.account().name());
        }
        if (removals.isEmpty() && adds.isEmpty() && games.isEmpty()) return null;
        List<String> problems = new ArrayList<>();
        // asked before the instance starts, so the user isn't called back mid-way
        for (String email : adds) {
            if (passwords.left(email)) {
                problems.add(account(adds, email) + ": skipped, as it was left out earlier in this batch.");
            } else if (passwords.of(email) == null) {
                problems.add(account(adds, email) + ": skipped, no password given.");
            }
        }
        if (context.cancelled()) return "Cancelled before " + instance.name() + " was changed.";
        adds.removeIf(passwords::left);
        if (removals.isEmpty() && adds.isEmpty() && games.isEmpty()) return String.join(" ", problems);
        boolean wasRunning = host.running(instance);
        if (!wasRunning) {
            context.step("Starting " + instance.name() + "…");
            String failed = host.start(instance, context::step);
            if (failed != null) {
                problems.addFirst(failed);
                return String.join(" ", problems);
            }
        }
        boolean finished;
        try {
            finished = change(instance, removals, adds, games, passwords, listedAs, problems, context);
        } finally {
            if (!wasRunning) {
                context.step("Stopping " + instance.name() + " again, as it was…");
                String failed = host.stop(instance);
                if (failed != null) problems.add(instance.name() + " is still running: " + failed);
            }
        }
        if (!finished) return cancelled(problems);
        return problems.isEmpty() ? null : String.join(" ", problems);
    }

    /** The removals, sign-ins and installs; false when cancelled part way. */
    private boolean change(EmulatorInstance instance, List<String> removals, List<String> adds, List<GameRecord> games,
                           Passwords passwords, ListedAs listedAs, List<String> problems, Operation.Context context)
            throws Exception {
        if (!removals.isEmpty() || !adds.isEmpty()) {
            try {
                if (!accounts(instance, removals, adds, passwords, listedAs, problems, context)) return false;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // the games need no account device: they are installed all the same
                problems.add("The Google accounts were not changed: " + e.getMessage());
            }
        }
        for (GameRecord game : games) {
            if (context.cancelled()) return false;
            context.step("Installing " + game.label() + "…");
            GameInstall.Result result = host.install(instance, game, context::step);
            if (!result.outcome().done()) problems.add(game.label() + ": " + result.message());
        }
        return true;
    }

    /** The removals, then the sign-ins, over one account device; false when cancelled part way. */
    private boolean accounts(EmulatorInstance instance, List<String> removals, List<String> adds, Passwords passwords,
                             ListedAs listedAs, List<String> problems, Operation.Context context) throws Exception {
        try (AccountDevice device = host.device(instance)) {
            if (!removals.isEmpty()) {
                String failed = new InstanceSignOut(device, pace).signOutAll(removals, context);
                if (context.cancelled()) return false;
                if (failed != null) problems.add(failed);
            }
            for (String email : adds) {
                if (passwords.left(email)) continue;
                if (context.cancelled()) return false;
                context.step("Signing in to " + account(adds, email) + "…");
                InstanceSignIn.Result result = new InstanceSignIn(device, pace)
                        .signIn(email, passwords.of(email), context);
                if (result.end().dropsAccount()) passwords.drop(email);
                if (!result.signedIn()) problems.add(account(adds, email) + ": " + result.words());
                else if (result.listedAs() != null) {
                    listedAs.listed(email, result.listedAs());
                    context.step(account(adds, email) + ": " + result.words());
                }
            }
        }
        return true;
    }

    private static String account(List<String> adds, String email) {
        return "Google account " + (adds.indexOf(email) + 1) + " of " + adds.size();
    }

    private static String cancelled(List<String> problems) {
        return "Cancelled." + (problems.isEmpty() ? "" : " " + String.join(" ", problems));
    }
}
