// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.google;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorOwnApps;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What Apply does on a running instance past its settings, one instance at a time: the Google accounts its configuration
 * drops are removed first, then those it lacks are signed in, then its missing games are installed. A stopped instance
 * is started for it and stopped again after. Facebook and mail accounts are records only and are left alone.
 * <p>
 * The passwords are the batch's ({@link Passwords}): read from the store or asked before the instance starts, remembered
 * once their sign-in is done when the user asked, forgotten when Google refuses one, and an account whose sign-in must
 * not be tried again is dropped from the rest of the batch. No outcome names an email: accounts are counted.
 * <p>
 * The sign-ins are readied ({@link Host#ready}) and paced ({@link SignInPace}): each waits its turn, one every 5 to 10
 * minutes from this PC and at most 8 a day, so another instance's operation boots and readies meanwhile. A day at its
 * cap starts no instance for sign-ins.
 */
public final class InstanceChanges {

    /** What the changes do to the world; {@link #live()} is this PC's. */
    public interface Host {
        boolean running(EmulatorInstance instance);

        /** Null when it started, else why not. */
        String start(EmulatorInstance instance, Operation.Context context) throws Exception;

        /** Null when it stopped, else why not. */
        String stop(EmulatorInstance instance) throws Exception;

        AccountDevice device(EmulatorInstance instance) throws Exception;

        GameInstall.Result install(EmulatorInstance instance, GameRecord game, Consumer<String> progress);

        /**
         * Why the device at {@code instance}'s address is another emulator ({@link EmulatorOwnApps#notThis}), else
         * empty: nothing is changed then, games included.
         */
        default Optional<String> notThis(EmulatorInstance instance) {
            return Optional.empty();
        }

        /**
         * Readies the running instance for its sign-ins ({@link SignInReady}): its identity checked, its clock, time
         * zone and region set, and three minutes since it booted. Null when ready, else why not.
         */
        default String ready(EmulatorInstance instance, Operation.Context context) throws Exception {
            return null;
        }

        /** Keeps the instance's identity as it is now, once an account is signed in on it. */
        default void signedIn(EmulatorInstance instance) {}

        static Host live() {
            return new Host() {
                @Override
                public boolean running(EmulatorInstance instance) {
                    return ApplyHost.live().running(instance);
                }

                @Override
                public String start(EmulatorInstance instance, Operation.Context context) throws Exception {
                    return ApplyHost.live().start(instance, context);
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

                @Override
                public String ready(EmulatorInstance instance, Operation.Context context) throws Exception {
                    return SignInReady.ready(instance, context);
                }

                @Override
                public void signedIn(EmulatorInstance instance) {
                    SignInReady.signedIn(instance);
                }

                @Override
                public Optional<String> notThis(EmulatorInstance instance) {
                    try (AdbDevice ignored = AdbDevice.connect(instance)) {
                        return Optional.empty();
                    } catch (IllegalStateException another) {
                        return Optional.of(another.getMessage());
                    } catch (RuntimeException e) {
                        return Optional.empty(); // unreachable: the device opened next says so itself
                    }
                }
            };
        }
    }

    private final Host host;
    private final Pace pace;
    private final SignInPace signIns;
    private final SignInDriver driver;

    public InstanceChanges(Host host) {
        this(host, SignInDriver.SCREENS);
    }

    /** Each sign-in driven by {@code driver}; the queue, the pacing and the passwords stay here. */
    public InstanceChanges(Host host, SignInDriver driver) {
        this(host, Pace.REAL, SignInPace.live(), driver);
    }

    InstanceChanges(Host host, Pace pace, SignInPace signIns) {
        this(host, pace, signIns, InstanceSignIn.driver(pace));
    }

    InstanceChanges(Host host, Pace pace, SignInPace signIns, SignInDriver driver) {
        this.host = host;
        this.pace = pace;
        this.signIns = signIns;
        this.driver = Objects.requireNonNull(driver, "driver");
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
        return run(instance, accounts, games, passwords, InstanceSignIn.SecondStep.ASK, listedAs, context);
    }

    /** As {@link #run(EmulatorInstance, List, List, Passwords, ListedAs, Operation.Context)}, each sign-in's second
     * step following {@code second}. */
    public String run(EmulatorInstance instance, List<ConfigDiff.AccountChange> accounts, List<GameRecord> games,
                      Passwords passwords, InstanceSignIn.SecondStep second, ListedAs listedAs,
                      Operation.Context context) throws Exception {
        List<String> removals = new ArrayList<>();
        List<String> adds = new ArrayList<>();
        for (ConfigDiff.AccountChange change : accounts) {
            if (!change.account().kind().signedInByBotMaker()) continue;
            (change.op() == ConfigDiff.AccountChange.Op.REMOVE ? removals : adds).add(change.account().name());
        }
        if (removals.isEmpty() && adds.isEmpty() && games.isEmpty()) return null;
        List<String> problems = new ArrayList<>();
        // the day's cap reached: no password is asked, nor the instance started, for sign-ins that can't go today
        if (!adds.isEmpty()) {
            Optional<String> capped = signIns.capped();
            if (capped.isPresent()) {
                problems.add(adds.size() == 1 ? "Its Google sign-in waits: " + capped.get()
                        : "Its " + adds.size() + " Google sign-ins wait: " + capped.get());
                adds.clear();
                if (removals.isEmpty() && games.isEmpty()) return String.join(" ", problems);
            }
        }
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
            String failed = host.start(instance, context);
            if (failed != null) {
                problems.addFirst(failed);
                return String.join(" ", problems);
            }
        }
        boolean finished;
        try {
            Optional<String> other = host.notThis(instance);
            if (other.isPresent()) {
                problems.addFirst(other.get());
                return String.join(" ", problems);
            }
            finished = change(instance, removals, adds, games, passwords, second, listedAs, problems, context);
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
                           Passwords passwords, InstanceSignIn.SecondStep second, ListedAs listedAs,
                           List<String> problems, Operation.Context context) throws Exception {
        if (!removals.isEmpty() || !adds.isEmpty()) {
            try {
                if (!accounts(instance, removals, adds, passwords, second, listedAs, problems, context)) return false;
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
                             InstanceSignIn.SecondStep second, ListedAs listedAs, List<String> problems,
                             Operation.Context context) throws Exception {
        try (AccountDevice device = host.device(instance)) {
            if (!removals.isEmpty()) {
                String failed = new InstanceSignOut(device, pace).signOutAll(removals, context);
                if (context.cancelled()) return false;
                if (failed != null) problems.add(failed);
            }
            if (adds.stream().allMatch(passwords::left)) return true;
            // another lane may have filled the day while this one started: nothing readied for nothing
            Optional<String> capped = signIns.capped();
            if (capped.isPresent()) {
                problems.add("Its Google sign-ins wait: " + capped.get());
                return true;
            }
            String notReady = host.ready(instance, context);
            if (context.cancelled()) return false;
            if (notReady != null) {
                problems.add(notReady);
                return true;
            }
            for (String email : adds) {
                if (passwords.left(email)) continue;
                if (context.cancelled()) return false;
                String waits = signIns.await(context);
                if (context.cancelled()) return false;
                if (waits != null) {
                    long left = adds.stream().skip(adds.indexOf(email)).filter(e -> !passwords.left(e)).count();
                    problems.add((left == 1 ? account(adds, email) + " was not signed in: "
                            : left + " Google accounts were not signed in: ") + waits);
                    return true;
                }
                char[] password = passwords.of(email);
                if (password == null) {
                    signIns.done();
                    problems.add(account(adds, email) + ": skipped, no password given.");
                    continue;
                }
                context.step("Signing in to " + account(adds, email) + "…");
                SignInDriver.Attempt attempt;
                try {
                    attempt = driver.signIn(instance, device, email, password, second, context);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    // this account's driver failed, not the instance: the next accounts are still tried
                    problems.add(account(adds, email) + ": the sign-in did not run: " + e.getMessage());
                    continue;
                } finally {
                    signIns.done();
                }
                InstanceSignIn.Result result = attempt.result();
                if (result.signedIn()) host.signedIn(instance);
                // kept before a failed end drops it: Google took it, a retry shouldn't ask again
                if (!result.signedIn() && attempt.passwordAccepted() && !passwords.accepted(email)) {
                    problems.add(account(adds, email) + ": this PC's credential store refused to remember its"
                            + " password.");
                }
                switch (result.end()) {
                    case WRONG_PASSWORD -> passwords.refused(email);
                    case NO_SUCH_ACCOUNT, CANNOT_TYPE -> passwords.discard(email);
                    default -> {
                        if (result.end().dropsAccount()) passwords.drop(email);
                    }
                }
                if (result.signedIn() && !passwords.done(email, result.listedAs())) {
                    context.step(account(adds, email) + ": signed in, but this PC's credential store refused to"
                            + " remember its password.");
                }
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
