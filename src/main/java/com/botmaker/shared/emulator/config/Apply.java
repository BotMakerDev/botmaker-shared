package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.checks.WindowsBackups;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Makes an instance what its configuration says, for the fields a {@link ConfigDiff} marks {@link ConfigDiff.Status#CHANGE}:
 * stop it when a setting needs that, keep the files it will edit, write the console, then the files, start it again,
 * run Android's commands, read it again, and log what changed in {@link Applies} so Undo can take it back.
 * <p>
 * On a failure or a cancel it puts back what it changed: the kept files, the console's old values, and Android's old
 * values when it got that far; then it starts the instance again only if it was running. A restart that fails leaves
 * the instance stopped, and the outcome says why.
 */
public final class Apply {

    /** One field changed: what it was (null when it was never read) and what it is to be. */
    public record Change(ConfigField field, ConfigValue before, ConfigValue after) {
    }

    /**
     * How an Apply ended.
     *
     * @param failed  null when it worked, else why not, in the user's words
     * @param written the changes made and logged; empty on a failure, as it put them back
     * @param refused the wanted values this product can't take, and why
     * @param facts   the instance read again at the end, or null when it could not be
     * @param applyId the log's id for {@link Applies#undo}, or null
     */
    public record Outcome(String failed, List<Change> written, Map<ConfigField, String> refused, InstanceFacts facts,
                         String applyId) {
    }

    private final ApplyHost host;
    private final EmulatorInstance instance;
    private final InstanceFacts facts;
    private final Applies log;

    /**
     * @param facts what the instance had when the diff was made, for the values a product writes together
     * @param log   where the changes are logged, or null to log nothing (an Undo marks its own line)
     */
    public Apply(ApplyHost host, EmulatorInstance instance, InstanceFacts facts, Applies log) {
        this.host = host;
        this.instance = instance;
        this.facts = facts;
        this.log = log;
    }

    /** The changes Apply makes for {@code diff}. */
    public static List<Change> changes(ConfigDiff diff) {
        List<Change> out = new ArrayList<>();
        for (ConfigDiff.FieldState state : diff.fields()) {
            if (state.status() == ConfigDiff.Status.CHANGE) {
                out.add(new Change(state.field(), state.now().map(InstanceFacts.Read::value).orElse(null),
                        state.wanted().value()));
            }
        }
        return out;
    }

    /** Applies {@code changes}, telling {@code context} each step. */
    public Outcome run(List<Change> changes, Operation.Context context) throws Exception {
        Map<ConfigField, ConfigValue> now = now();
        Map<ConfigField, ConfigValue> after = new EnumMap<>(ConfigField.class);
        changes.forEach(c -> after.put(c.field(), c.after()));
        ProductWriters.Plan plan = ProductWriters.of(instance, after, now);
        List<Change> written = changes.stream().filter(c -> !plan.refused().containsKey(c.field())).toList();
        if (plan.isEmpty()) {
            return new Outcome(plan.refused().isEmpty() ? "Nothing to change." : "Nothing BotMaker can change here.",
                    List.of(), plan.refused(), facts, null);
        }
        Undoing undo = new Undoing(written, now);
        boolean wasRunning = host.running(instance);
        try {
            return run(plan, written, undo, wasRunning, context);
        } catch (InterruptedException e) {
            Thread.interrupted(); // put back first, then say it was stopped
            Outcome back = undo.back("Stopped.", wasRunning, plan.refused(), context);
            Thread.currentThread().interrupt();
            return back;
        } catch (Exception e) {
            return undo.back(Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()), wasRunning,
                    plan.refused(), context);
        }
    }

    private Outcome run(ProductWriters.Plan plan, List<Change> written, Undoing undo, boolean wasRunning,
                        Operation.Context context) throws Exception {
        int steps = 3 + plan.stopped().size() + plan.running().size();
        int[] done = {0};
        Runnable tick = () -> context.progress(++done[0] / (double) steps);

        if (!plan.stopped().isEmpty() && wasRunning) {
            context.step("Stopping " + instance.name() + " to change its settings");
            String failed = host.stop(instance);
            if (failed != null) return failure(failed, plan.refused());
        }
        tick.run();
        for (SettingWrite write : plan.stopped()) {
            if (write instanceof SettingWrite.FileEdit edit && !undo.kept.containsKey(edit.file())) {
                context.step("Keeping a copy of " + edit.file().getFileName());
                try {
                    undo.kept.put(edit.file(), host.keep(instance, edit.file()));
                } catch (Exception e) {
                    return undo.back("BotMaker could not keep a copy of " + edit.file().getFileName() + ": "
                            + e.getMessage(), wasRunning, plan.refused(), context);
                }
            }
        }
        for (SettingWrite write : plan.stopped()) {
            if (context.cancelled()) return undo.back("Cancelled.", wasRunning, plan.refused(), context);
            context.step(write.what());
            if (write instanceof SettingWrite.Console console) undo.consoleFields.addAll(console.fields());
            String failed = switch (write) {
                case SettingWrite.Console console -> host.console(console.command());
                case SettingWrite.FileEdit edit -> host.edit(edit.file(), edit.edit());
                case SettingWrite.Shell shell -> "A command for Android came before the start.";
            };
            if (failed != null) return undo.back(failed, wasRunning, plan.refused(), context);
            tick.run();
        }
        boolean startedHere = false;
        if ((wasRunning || !plan.running().isEmpty()) && !host.running(instance)) {
            if (context.cancelled()) return undo.back("Cancelled.", wasRunning, plan.refused(), context);
            context.step("Starting " + instance.name());
            String failed = host.start(instance, context::step);
            if (failed != null) return undo.back(failed, wasRunning, plan.refused(), context);
            startedHere = !wasRunning;
        }
        tick.run();
        for (SettingWrite write : plan.running()) {
            if (context.cancelled()) return undo.back("Cancelled.", wasRunning, plan.refused(), context);
            context.step(write.what());
            undo.androidRan = true;
            if (write instanceof SettingWrite.Shell shell) {
                for (String command : shell.commands()) {
                    String failed = host.shell(instance, command, shell.root());
                    if (failed == null) continue;
                    if (shell.required()) return undo.back(failed, wasRunning, plan.refused(), context);
                    context.step("Android refused one of its commands, and the rest go on: " + failed);
                }
            }
            tick.run();
        }
        // read while it runs: a stopped instance keeps Android's values from before
        context.step("Reading " + instance.name() + " again");
        InstanceFacts read = host.read(instance, facts);
        tick.run();
        if (startedHere) {
            context.step("Stopping " + instance.name() + " again, as it was");
            String failed = host.stop(instance);
            if (failed != null) context.step("It is still running: " + failed);
        }
        String id = null;
        if (log != null) {
            try {
                id = log.record(InstanceKey.of(instance), written);
            } catch (Exception e) {
                context.step("The change is made, but its log could not be written, so Undo can't take it back: "
                        + e.getMessage());
            }
        }
        return new Outcome(null, written, plan.refused(), read, id);
    }

    /**
     * Takes back {@code entry}: each change the other way round (one whose old value was never read stays), and marks
     * it undone in {@code log} when that worked.
     */
    public static Outcome undo(ApplyHost host, EmulatorInstance instance, InstanceFacts facts, Applies log,
                               Applies.Entry entry, Operation.Context context) throws Exception {
        List<Change> back = entry.reversed();
        if (back.isEmpty()) return new Outcome("Nothing to undo: what it changed was never read before.", List.of(),
                Map.of(), facts, null);
        Outcome outcome = new Apply(host, instance, facts, null).run(back, context);
        if (outcome.failed() == null && !outcome.refused().isEmpty()) {
            // part of it is back: the line stays, so Undo can be tried again once the rest can be written
            return new Outcome("Undone, except: " + said(outcome.refused()), outcome.written(), outcome.refused(),
                    outcome.facts(), null);
        }
        if (outcome.failed() == null) {
            log.undone(entry.id());
            return new Outcome(null, outcome.written(), outcome.refused(), outcome.facts(), entry.id());
        }
        return outcome;
    }

    private Map<ConfigField, ConfigValue> now() {
        Map<ConfigField, ConfigValue> now = new EnumMap<>(ConfigField.class);
        if (facts != null) {
            for (ConfigField field : ConfigField.values()) {
                facts.field(field).ifPresent(read -> now.put(field, read.value()));
            }
        }
        return now;
    }

    private Outcome failure(String why, Map<ConfigField, String> refused) {
        return new Outcome(why, List.of(), refused, null, null);
    }

    private static String said(Map<ConfigField, String> refused) {
        List<String> out = new ArrayList<>();
        refused.forEach((field, why) -> out.add(field.displayName() + ": " + why));
        return String.join("; ", out);
    }

    /** What a failure puts back. */
    private final class Undoing {
        final Map<Path, WindowsBackups.Backup> kept = new LinkedHashMap<>();
        final Set<ConfigField> consoleFields = EnumSet.noneOf(ConfigField.class);
        final ProductWriters.Plan before;
        final Set<ConfigField> neverRead = EnumSet.noneOf(ConfigField.class);
        boolean androidRan;

        Undoing(List<Change> written, Map<ConfigField, ConfigValue> now) {
            Map<ConfigField, ConfigValue> old = new EnumMap<>(ConfigField.class);
            for (Change change : written) {
                if (change.before() != null) old.put(change.field(), change.before());
                else neverRead.add(change.field());
            }
            before = old.isEmpty() ? new ProductWriters.Plan(List.of(), List.of(), Map.of())
                    : ProductWriters.of(instance, old, now);
        }

        Outcome back(String why, boolean wasRunning, Map<ConfigField, String> refused, Operation.Context context) {
            List<String> problems = new ArrayList<>();
            boolean consoleRan = !consoleFields.isEmpty();
            for (ConfigField field : consoleFields) {
                if (neverRead.contains(field)) {
                    problems.add(field.displayName() + " stays as set: BotMaker never read what it was");
                }
            }
            if (consoleRan || androidRan) before.refused().forEach((field, cannot) ->
                    problems.add(field.displayName() + " stays as set: " + cannot));
            try {
                if (androidRan && host.running(instance)) {
                    context.step("Putting Android's values back");
                    for (SettingWrite write : before.running()) {
                        if (write instanceof SettingWrite.Shell shell) {
                            for (String command : shell.commands()) add(problems, host.shell(instance, command,
                                    shell.root()));
                        }
                    }
                }
                if (!kept.isEmpty() || consoleRan) {
                    if (host.running(instance)) {
                        context.step("Stopping " + instance.name() + " to put its settings back");
                        add(problems, host.stop(instance));
                    }
                    for (WindowsBackups.Backup backup : kept.values()) {
                        context.step("Putting " + backup.origin().getFileName() + " back");
                        add(problems, host.restore(backup));
                    }
                    if (consoleRan) {
                        for (SettingWrite write : before.stopped()) {
                            if (write instanceof SettingWrite.Console console) {
                                context.step("Putting back: " + console.what().toLowerCase());
                                add(problems, host.console(console.command()));
                            }
                        }
                    }
                }
                String end = why + (problems.isEmpty() ? " What was changed is put back."
                        : " Putting it back also failed: " + String.join("; ", problems));
                if (wasRunning && !host.running(instance)) {
                    context.step("Starting " + instance.name() + " again");
                    String failed = host.start(instance, context::step);
                    if (failed != null) end += " " + instance.name() + " stays stopped: " + failed;
                } else if (!wasRunning && host.running(instance)) {
                    host.stop(instance);
                }
                return failure(end, refused);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(why + " Putting it back was stopped.", refused);
            } catch (Exception e) {
                return failure(why + " Putting it back failed: " + Optional.ofNullable(e.getMessage())
                        .orElse(e.getClass().getSimpleName()), refused);
            }
        }

        private static void add(List<String> problems, String failed) {
            if (failed != null) problems.add(failed);
        }
    }
}
