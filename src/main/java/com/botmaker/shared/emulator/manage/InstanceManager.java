// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.manage;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorPlatform;
import com.botmaker.shared.emulator.HeldInstances;
import com.botmaker.shared.emulator.NewInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.config.AndroidState;
import com.botmaker.shared.emulator.config.Applies;
import com.botmaker.shared.emulator.config.Apply;
import com.botmaker.shared.emulator.config.ApplyHost;
import com.botmaker.shared.emulator.config.ConfigField;
import com.botmaker.shared.emulator.config.ConfigValue;
import com.botmaker.shared.emulator.config.InstanceFacts;
import com.botmaker.shared.emulator.config.LightProfile;
import com.botmaker.shared.emulator.config.Operation;
import com.botmaker.shared.emulator.config.ProductSettings;
import com.botmaker.shared.emulator.google.AccountDevice;
import com.botmaker.shared.emulator.google.InstanceSignOut;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * How BotMaker manages a product's instances: through its console ({@link Console}: LDPlayer, MuMu, MEmu), or by
 * opening the product's own manager where it has none ({@link OpensManager}: BlueStacks and MSI). A console's work is
 * an {@link Operation.Body} the caller queues in {@code EmulatorOperations}.
 * <p>
 * Each body finds its instance again by {@link EmulatorInstance#identity()} before acting, so a list read a while ago
 * never selects another instance by a stale index, and judges the console by what discovery lists after it, not by
 * its exit code alone: LDPlayer 14's {@code ldconsole add} exits 1 after creating an instance.
 */
public sealed interface InstanceManager {

    PlatformId product();

    /** This PC's way for {@code product}, or null when it isn't installed or offers none. */
    static InstanceManager forProduct(PlatformId product) {
        for (EmulatorPlatform platform : Platforms.ALL) {
            if (platform.id() != product) continue;
            try {
                return of(platform.newInstance(), ManageHost.live());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** The way {@code way} names: its manager, or the console its create command runs. Null for none. */
    static InstanceManager of(NewInstance way, ManageHost host) {
        if (way == null || way.command().isEmpty()) return null;
        if (way.opensManager()) return new OpensManager(way.platformId(), way.command());
        InstanceConsole console = InstanceConsole.of(way.platformId(), way.command().getFirst());
        return console == null ? null : new Console(console, host, new SecureRandom());
    }

    /** BlueStacks' Multi-Instance Manager: the user creates, copies, renames and deletes there. */
    record OpensManager(PlatformId product, List<String> command) implements InstanceManager {

        public OpensManager {
            command = List.copyOf(command);
        }

        /** Opens it, through Windows' permission prompt where MSI's asks for one, and says so. */
        public String open() throws IOException {
            if (!EmulatorLauncher.open(command)) {
                throw new IOException(product.displayName() + "'s instance manager didn't open.");
            }
            return product.displayName() + "'s instance manager is open: make the change there, then refresh.";
        }
    }

    /** Removes the Google accounts a clone copied, on the running clone. Null when it worked, else why not. */
    @FunctionalInterface
    interface AccountRemoval {
        String remove(EmulatorInstance clone, Set<String> emails, Operation.Context context) throws Exception;

        /** Through Android Settings, one account after another ({@link InstanceSignOut}). */
        AccountRemoval SIGN_OUT = (clone, emails, context) -> {
            try (AccountDevice device = AccountDevice.of(clone)) {
                String failed = InstanceSignOut.all(device, List.copyOf(emails), context);
                return failed == null ? null : "The accounts copied from its source: " + failed;
            }
        };
    }

    /**
     * What a clone gets once its accounts are gone, or a made instance before its first start: its configuration's
     * Apply. Null when it worked.
     */
    @FunctionalInterface
    interface Prepare {
        String run(EmulatorInstance clone, Operation.Context context) throws Exception;

        Prepare NOTHING = (clone, context) -> null;

        /**
         * The light profile ({@link LightProfile}) written on the stopped instance through {@link Apply}, logged in
         * {@code log} for Undo with the values its settings had.
         */
        static Prepare light(ApplyHost host, Applies log) {
            return (instance, context) -> {
                Map<ConfigField, ConfigValue> fields = LightProfile.fields(ProductSettings.of(instance.platformId()));
                if (fields.isEmpty()) return null;
                InstanceFacts before = host.read(instance, null);
                List<Apply.Change> changes = fields.entrySet().stream()
                        .map(e -> new Apply.Change(e.getKey(), before == null ? null
                                : before.field(e.getKey()).map(InstanceFacts.Read::value).orElse(null), e.getValue()))
                        .toList();
                return new Apply(host, instance, before, log).run(changes, context).failed();
            };
        }
    }

    /** A product's console, driven through a {@link ManageHost}. */
    final class Console implements InstanceManager {

        /** What a name may hold: a console's listing is comma-separated, and a target's {@code @} splits a name. */
        private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} ._()\\-]{1,40}");

        /**
         * One create or copy per product at a time, from its first listing to its second: two at once (a create on its
         * product's lane, a clone on its source's port lane) would each take the other's new instance for its own.
         */
        private static final Map<PlatformId, Object> MAKING = new EnumMap<>(PlatformId.class);

        private final InstanceConsole console;
        private final ManageHost host;
        private final RandomGenerator random;

        Console(InstanceConsole console, ManageHost host, RandomGenerator random) {
            this.console = console;
            this.host = host;
            this.random = random;
        }

        @Override
        public PlatformId product() {
            return console.product();
        }

        /**
         * Why {@code name} can't name an instance of this product, or null when it can: blank, too long, a character a
         * console or a launch target can't carry, or the name of another of its instances ({@code except} aside).
         */
        public static String nameProblem(String name, List<EmulatorInstance> existing, EmulatorInstance except) {
            String n = name == null ? "" : name.strip();
            if (n.isEmpty()) return "Give it a name.";
            if (!NAME.matcher(n).matches()) {
                return "Use up to 40 letters, digits, spaces and . _ ( ) - only.";
            }
            String wanted = n.toLowerCase(Locale.ROOT);
            for (EmulatorInstance other : existing) {
                if (except != null && other.identity().equals(except.identity())) continue;
                if (other.name() != null && other.name().strip().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return "Another " + other.brand() + " instance is called " + other.name() + ".";
                }
            }
            return null;
        }

        /**
         * The names to make so that {@code product} has {@code count} instances, none when it has them: its name and
         * the first free numbers, "LDPlayer-1", "LDPlayer-2".
         */
        public static List<String> missingNames(PlatformId product, List<EmulatorInstance> existing, int count) {
            List<String> out = new ArrayList<>();
            for (int n = 1; existing.size() + out.size() < count && n <= existing.size() + count; n++) {
                String name = product.displayName() + "-" + n;
                if (nameProblem(name, existing, null) == null) out.add(name);
            }
            return out;
        }

        /**
         * Makes a fresh instance called {@code name}, ready for its one account: created, new device IDs, ADB on and
         * {@code stopped} run while it is stopped (the light profile, {@link Prepare#light}), then a first start held
         * from every bot that gives it its BotMaker ID file ({@link ManageHost#mark}), and stopped again. Nothing is made
         * once the product has {@code upTo} instances, counted as the operation runs. {@code made} hears it as soon as
         * it exists, so a window lists it even when a later step fails.
         */
        public Operation.Body make(String name, int upTo, Prepare stopped, Consumer<EmulatorInstance> made) {
            return context -> {
                String n = name == null ? "" : name.strip();
                List<EmulatorInstance> all = host.instances(product());
                if (all.size() >= upTo) {
                    return product().displayName() + " has " + all.size() + " instances already, so " + n
                            + " was not made.";
                }
                String problem = nameProblem(n, all, null);
                if (problem != null) return problem;
                context.step("Creating " + n + "…");
                context.progress(0.05);
                EmulatorInstance fresh = madeBy(console.create(n), n, made, context);
                try (HeldInstances.Hold ignored = HeldInstances.hold(fresh)) {
                    context.step("Giving " + n + " new device IDs…");
                    context.progress(0.3);
                    String ids = newIds(fresh);
                    if (ids != null) return ids + " " + n + " was not started.";
                    String adb = host.adbOn(fresh);
                    if (adb != null) return adb + " " + n + " was not started.";
                    if (context.cancelled()) return "Cancelled: " + n + " was made and never started.";
                    context.step("Applying its configuration…");
                    context.progress(0.45);
                    String prepared = stopped.run(fresh, context);
                    if (prepared != null) return prepared + " " + n + " was not started.";
                    context.step("Starting " + n + ", held from bots, to give it its BotMaker ID…");
                    context.progress(0.6);
                    String outcome;
                    try {
                        String started = host.start(fresh, context);
                        outcome = started != null ? started : host.mark(upNow(fresh));
                    } catch (Exception e) {
                        outcome = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                    return joined(outcome, stopped(fresh, context));
                }
            };
        }

        /**
         * Creates an instance called {@code name}, with ADB on (LDPlayer's is off in a new one, 2026-11-12);
         * {@code made} hears it once it exists.
         */
        public Operation.Body create(String name, Consumer<EmulatorInstance> made) {
            return context -> {
                String n = name == null ? "" : name.strip();
                List<EmulatorInstance> all = host.instances(product());
                String problem = nameProblem(n, all, null);
                if (problem != null) return problem;
                context.step("Creating " + n + "…");
                return host.adbOn(madeBy(console.create(n), n, made, context));
            };
        }

        /**
         * Copies {@code source} as {@code name}, stopped: new device IDs and ADB on, then a first start held from every
         * bot ({@link HeldInstances}), the copied Google accounts removed, {@code prepare} run, and the clone stopped
         * again. {@code made} hears the clone as soon as it exists, so a window lists it even when a later step fails.
         */
        public Operation.Body clone(EmulatorInstance source, String name, AccountRemoval removal, Prepare prepare,
                                    Consumer<EmulatorInstance> made) {
            return context -> {
                String n = name == null ? "" : name.strip();
                List<EmulatorInstance> all = host.instances(product());
                EmulatorInstance from = find(all, source);
                if (from == null) return gone(source);
                String problem = nameProblem(n, all, null);
                if (problem != null) return problem;
                if (host.running(from)) return "Stop " + from.name() + " first: a running instance isn't copied whole.";
                context.step("Copying " + from.name() + "…");
                context.progress(0.1);
                EmulatorInstance clone = madeBy(console.copy(from, n), n, made, context);
                try (HeldInstances.Hold ignored = HeldInstances.hold(clone)) {
                    context.step("Giving " + n + " new device IDs…");
                    context.progress(0.4);
                    String ids = newIds(clone);
                    if (ids != null) return ids + " " + n + " was not started.";
                    if (context.cancelled()) return "Cancelled: " + n + " was copied and never started.";
                    context.step("Starting " + n + ", held from bots…");
                    context.progress(0.5);
                    String adb = host.adbOn(clone);
                    if (adb != null) return adb + " " + n + " was not started.";
                    String outcome;
                    try {
                        String started = host.start(clone, context);
                        // it carries its source's ID file, so every checked connect refuses it until it has its own
                        String marked = started == null ? host.mark(upNow(clone)) : null;
                        if (marked != null) {
                            outcome = marked + " The Google accounts it copied are still on it: remove them in its "
                                    + "Settings › Accounts before a bot uses it.";
                        } else {
                            outcome = started != null ? started : prepared(clone, removal, prepare, context);
                        }
                    } catch (Exception e) {
                        outcome = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                    return joined(outcome, stopped(clone, context));
                }
            };
        }

        /** Stops the clone again; null when it is stopped, else why it still runs. */
        private String stopped(EmulatorInstance clone, Operation.Context context) {
            try {
                if (!host.running(clone)) return null;
                context.step("Stopping " + clone.name() + "…");
                String failed = host.stop(clone);
                return failed == null ? null : failed + " " + clone.name() + " still runs: stop it before a bot "
                        + "uses it.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return clone.name() + " may still run: stop it before a bot uses it.";
            } catch (Exception e) {
                return clone.name() + " may still run (" + e.getMessage() + "): stop it before a bot uses it.";
            }
        }

        private static String joined(String outcome, String stop) {
            if (stop == null) return outcome == null ? null : outcome + " It is stopped.";
            return outcome == null ? stop : outcome + " " + stop;
        }

        private String prepared(EmulatorInstance clone, AccountRemoval removal, Prepare prepare,
                                Operation.Context context) throws Exception {
            context.step("Removing the Google accounts it copied…");
            context.progress(0.7);
            Set<String> accounts = host.googleAccounts(clone);
            if (accounts == null) {
                return "Android on " + clone.name() + " didn't list its accounts, so they weren't removed: check its "
                        + "Settings › Accounts before a bot uses it.";
            }
            if (!accounts.isEmpty()) {
                String refused = removal.remove(clone, accounts, context);
                if (refused != null) return refused;
                Set<String> left = host.googleAccounts(clone);
                if (left == null || !left.isEmpty()) {
                    return clone.name() + " still holds a Google account it copied: remove it in its Settings › "
                            + "Accounts before a bot uses it.";
                }
            }
            if (context.cancelled()) {
                return "Cancelled: " + clone.name() + " holds no copied account, and its configuration wasn't applied.";
            }
            context.step("Applying its configuration…");
            context.progress(0.85);
            return prepare.run(clone, context);
        }

        /** Renames {@code instance}; {@code renamed} hears it under its new name. */
        public Operation.Body rename(EmulatorInstance instance, String name, Consumer<EmulatorInstance> renamed) {
            return context -> {
                String n = name == null ? "" : name.strip();
                List<EmulatorInstance> all = host.instances(product());
                EmulatorInstance now = find(all, instance);
                if (now == null) return gone(instance);
                if (HeldInstances.heldElsewhere(now)) return HeldInstances.why(now);
                String problem = nameProblem(n, all, now);
                if (problem != null) return problem;
                context.step("Renaming " + now.name() + " to " + n + "…");
                renamed.accept(renamedTo(now, n));
                return null;
            };
        }

        /** Stops {@code instance} when it runs, then deletes it. The window asks first. */
        public Operation.Body delete(EmulatorInstance instance) {
            return context -> {
                EmulatorInstance now = find(host.instances(product()), instance);
                if (now == null) return null;
                if (HeldInstances.heldElsewhere(now)) return HeldInstances.why(now);
                if (host.running(now)) {
                    context.step("Stopping " + now.name() + "…");
                    String stopped = host.stop(now);
                    if (stopped != null) return stopped;
                }
                if (context.cancelled()) return "Cancelled: " + now.name() + " was not deleted.";
                context.step("Deleting " + now.name() + "…");
                Spawn.Completed done = host.console(console.delete(now));
                if (done == null) return late("deleting " + now.name());
                if (find(host.instances(product()), now) == null) return null;
                return refused(done, "didn't delete " + now.name());
            };
        }

        /**
         * Gives the stopped {@code instance} new device IDs, refused while it holds a Google account as BotMaker last saw
         * it ({@code last}, null when never read): Google would see that account's device change.
         */
        public Operation.Body newDeviceIds(EmulatorInstance instance, InstanceFacts last) {
            return context -> {
                EmulatorInstance now = find(host.instances(product()), instance);
                if (now == null) return gone(instance);
                if (host.running(now)) {
                    return "Stop " + now.name() + " first: its device IDs change only while it is stopped.";
                }
                Set<String> held = last == null ? Set.of()
                        : last.android().flatMap(AndroidState::googleAccounts).orElse(Set.of());
                if (!held.isEmpty()) {
                    return now.name() + " holds a Google account, so its device IDs stay: new ones would show Google a "
                            + "new device for it. Sign it out first, or make a new instance instead.";
                }
                context.step("Giving " + now.name() + " new device IDs…");
                return newIds(now);
            };
        }

        /**
         * Runs the console's ID commands. Never says a value or the console's answer: MuMu's carry the IDs drawn, and
         * an echo of one would land in an operation's outcome.
         */
        private String newIds(EmulatorInstance instance) throws Exception {
            for (List<String> command : console.newDeviceIds(instance, random)) {
                Spawn.Completed done = host.console(command);
                if (done == null) return late("changing " + instance.name() + "'s device IDs");
                if (!done.ok()) {
                    return program() + " refused new device IDs for " + instance.name() + " (exit "
                            + done.exitCode() + ").";
                }
            }
            return null;
        }

        /**
         * Runs a create or a copy, tells {@code made} what appeared, then names it {@code name} when the console
         * didn't. {@code made} hears it even when that rename fails, so the window still lists it.
         */
        private EmulatorInstance madeBy(List<String> command, String name, Consumer<EmulatorInstance> made,
                                        Operation.Context context) throws Exception {
            EmulatorInstance found = null;
            Spawn.Completed done;
            synchronized (MAKING.computeIfAbsent(product(), p -> new Object())) {
                Set<String> before = new HashSet<>();
                for (EmulatorInstance known : host.instances(product())) before.add(known.identity());
                done = host.console(command);
                if (done == null) throw new IOException(late("the new instance") + " It may still appear.");
                for (EmulatorInstance now : host.instances(product())) {
                    if (!before.contains(now.identity())) {
                        found = now;
                        break;
                    }
                }
            }
            if (found == null) throw new IOException(refused(done, "didn't make the instance"));
            if (name.equals(found.name())) {
                made.accept(found);
                return found;
            }
            context.step("Naming it " + name + "…");
            try {
                EmulatorInstance named = renamedTo(found, name);
                made.accept(named);
                return named;
            } catch (Exception e) {
                made.accept(found);
                throw new IOException(e.getMessage() + " It is listed as " + found.name() + ", with nothing else "
                        + "done to it.", e);
            }
        }

        private EmulatorInstance renamedTo(EmulatorInstance instance, String name) throws Exception {
            Spawn.Completed done = host.console(console.rename(instance, name));
            if (done == null) throw new IOException(late("renaming " + instance.name()));
            EmulatorInstance now = find(host.instances(product()), instance);
            if (now != null && name.equals(now.name())) return now;
            throw new IOException(refused(done, "didn't rename " + instance.name()));
        }

        /** {@code instance} as discovery lists it now that it runs, its address as its product gave it; else itself. */
        private EmulatorInstance upNow(EmulatorInstance instance) throws Exception {
            EmulatorInstance now = find(host.instances(product()), instance);
            return now == null ? instance : now;
        }

        /** {@code instance} as discovery lists it now, by its address; null when it is gone. */
        private static EmulatorInstance find(List<EmulatorInstance> all, EmulatorInstance instance) {
            for (EmulatorInstance now : all) {
                if (now.identity().equals(instance.identity())) return now;
            }
            return null;
        }

        private static String gone(EmulatorInstance instance) {
            return instance.name() + " is no longer there: refresh the list.";
        }

        private String late(String what) {
            return program() + " didn't finish " + what + " in time.";
        }

        private String refused(Spawn.Completed done, String what) {
            String said = done.output() == null ? "" : done.output().strip();
            return program() + " " + what + (said.isEmpty() ? " (exit " + done.exitCode() + ")." : ": " + said);
        }

        private String program() {
            return Path.of(console.exe()).getFileName().toString();
        }
    }
}
