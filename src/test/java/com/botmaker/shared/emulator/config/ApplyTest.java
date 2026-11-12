package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.QuietRun;
import com.botmaker.shared.emulator.checks.WindowsBackups;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Apply's order, its putting back on a failure, and its log, over a host that records what it is asked. */
class ApplyTest {

    private static final EmulatorInstance MEMU = new EmulatorInstance(PlatformId.MEMU, "MEmu", "127.0.0.1", 21503)
            .withCommands(List.of("memuc.exe", "start", "-n", "MEmu"), List.of("memuc.exe", "stop", "-n", "MEmu"));

    @TempDir
    Path dir;

    @Test
    void stopsWritesStartsRunsAndroidReadsThenLogs() throws Exception {
        FakeHost host = new FakeHost(true);
        Applies log = new Applies(dir.resolve("applies.jsonl"));
        Apply.Outcome outcome = new Apply(host, MEMU, facts(Map.of(ConfigField.CPU_CORES, new ConfigValue.Count(2))),
                log).run(List.of(
                new Apply.Change(ConfigField.CPU_CORES, new ConfigValue.Count(2), new ConfigValue.Count(4)),
                new Apply.Change(ConfigField.TIME_ZONE, null, new ConfigValue.Text("Europe/Paris"))), new Steps());

        assertNull(outcome.failed());
        assertEquals(List.of("stop", "console memuc.exe setconfigex -n MEmu cpus 4", "start",
                "shell settings put global auto_time_zone 0", "shell if cmd alarm", "read"), host.calls());
        assertEquals(2, outcome.written().size());
        Applies.Entry last = log.last(InstanceKey.of(MEMU)).orElseThrow();
        assertEquals(outcome.applyId(), last.id());
        assertEquals(List.of(new Apply.Change(ConfigField.CPU_CORES, new ConfigValue.Count(4),
                new ConfigValue.Count(2))), last.reversed(), "a time zone never read is not undone");
    }

    @Test
    void aStoppedInstanceIsStartedForAndroidAndStoppedAgain() throws Exception {
        FakeHost host = new FakeHost(false);
        Apply.Outcome outcome = new Apply(host, MEMU, facts(Map.of()), null).run(List.of(
                new Apply.Change(ConfigField.POPUPS_OFF, null, new ConfigValue.Toggle(true))), new Steps());
        assertNull(outcome.failed());
        assertEquals("start", host.calls().getFirst());
        assertEquals(List.of("read", "stop"), host.calls().subList(host.calls().size() - 2, host.calls().size()),
                "read while Android runs, then stopped as it was");
    }

    @Test
    void aBestEffortCommandAndroidRefusesDoesNotStopTheApply() throws Exception {
        FakeHost host = new FakeHost(true);
        host.failing.put("shell " + QuietRun.calmCommands().getFirst(), "no such package");
        Apply.Outcome outcome = new Apply(host, MEMU, facts(Map.of()), null).run(List.of(
                new Apply.Change(ConfigField.POPUPS_OFF, null, new ConfigValue.Toggle(true))), new Steps());
        assertNull(outcome.failed());
        assertTrue(host.calls().contains("shell " + QuietRun.calmCommands().getLast()), host.calls().toString());
    }

    @Test
    void anExceptionPutsBackLikeAFailure() throws Exception {
        FakeHost host = new FakeHost(true);
        host.throwing.add("console memuc.exe setconfigex -n MEmu memory 4096");
        Apply.Outcome outcome = new Apply(host, MEMU, facts(Map.of(ConfigField.CPU_CORES, new ConfigValue.Count(2),
                ConfigField.MEMORY, new ConfigValue.Megabytes(2048))), null).run(List.of(
                new Apply.Change(ConfigField.CPU_CORES, new ConfigValue.Count(2), new ConfigValue.Count(4)),
                new Apply.Change(ConfigField.MEMORY, new ConfigValue.Megabytes(2048),
                        new ConfigValue.Megabytes(4096))), new Steps());

        assertTrue(outcome.failed().startsWith("memuc broke"), outcome.failed());
        assertTrue(host.calls().contains("console memuc.exe setconfigex -n MEmu cpus 2"), host.calls().toString());
        assertTrue(host.up, "it was running, so it is started again");
    }

    @Test
    void aValueNeverReadIsSaidNotPutBack() throws Exception {
        FakeHost host = new FakeHost(true);
        host.failing.put("console memuc.exe setconfigex -n MEmu memory 4096", "memuc refused");
        Apply.Outcome outcome = new Apply(host, MEMU, facts(Map.of(ConfigField.MEMORY,
                new ConfigValue.Megabytes(2048))), null).run(List.of(
                new Apply.Change(ConfigField.CPU_CORES, null, new ConfigValue.Count(4)),
                new Apply.Change(ConfigField.MEMORY, new ConfigValue.Megabytes(2048),
                        new ConfigValue.Megabytes(4096))), new Steps());

        assertTrue(outcome.failed().contains("CPU cores stays as set"), outcome.failed());
        assertFalse(outcome.failed().contains("What was changed is put back"), outcome.failed());
    }

    @Test
    void restartFailsRestores() throws Exception {
        EmulatorInstance ld = new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1", 5555)
                .withCommands(List.of("C:/LDPlayer/ldconsole.exe", "launch", "--index", "0"), List.of());
        FakeHost host = new FakeHost(true);
        host.failing.put("start", "LDPlayer did not boot");
        Applies log = new Applies(dir.resolve("applies.jsonl"));

        Apply.Outcome outcome = new Apply(host, ld, facts(Map.of(ConfigField.FPS, new ConfigValue.Count(60))), log)
                .run(List.of(new Apply.Change(ConfigField.FPS, new ConfigValue.Count(60), new ConfigValue.Count(30))),
                        new Steps());

        assertNotNull(outcome.failed());
        assertTrue(outcome.failed().startsWith("LDPlayer did not boot"), outcome.failed());
        assertTrue(outcome.failed().contains("stays stopped"), outcome.failed());
        assertTrue(host.calls().contains("restore"), "the kept file is put back: " + host.calls());
        assertTrue(host.calls().indexOf("restore") > host.calls().indexOf("edit"));
        assertFalse(host.up, "a failed restart leaves it stopped");
        assertTrue(outcome.written().isEmpty());
        assertTrue(log.last(InstanceKey.of(ld)).isEmpty(), "nothing is logged");
    }

    @Test
    void aFailedConsoleWritePutsTheOldValuesBack() throws Exception {
        FakeHost host = new FakeHost(true);
        host.failing.put("console memuc.exe setconfigex -n MEmu memory 4096", "memuc refused");
        Apply.Outcome outcome = new Apply(host, MEMU, facts(Map.of(ConfigField.CPU_CORES, new ConfigValue.Count(2),
                ConfigField.MEMORY, new ConfigValue.Megabytes(2048))), null).run(List.of(
                new Apply.Change(ConfigField.CPU_CORES, new ConfigValue.Count(2), new ConfigValue.Count(4)),
                new Apply.Change(ConfigField.MEMORY, new ConfigValue.Megabytes(2048),
                        new ConfigValue.Megabytes(4096))), new Steps());

        assertTrue(outcome.failed().startsWith("memuc refused"), outcome.failed());
        assertTrue(host.calls().contains("console memuc.exe setconfigex -n MEmu cpus 2"), host.calls().toString());
        assertTrue(host.up, "it was running, so it is started again");
    }

    @Test
    void cancelledBeforeAWritePutsBackAndStops() throws Exception {
        FakeHost host = new FakeHost(true);
        Steps steps = new Steps();
        steps.cancelled = true;
        Apply.Outcome outcome = new Apply(host, MEMU, facts(Map.of()), null).run(List.of(
                new Apply.Change(ConfigField.FPS, null, new ConfigValue.Count(30))), steps);
        assertEquals("Cancelled. What was changed is put back.", outcome.failed());
        assertFalse(host.calls().stream().anyMatch(c -> c.startsWith("console")));
    }

    @Test
    void undoAppliesTheOldValuesAndMarksTheLine() throws Exception {
        FakeHost host = new FakeHost(true);
        Applies log = new Applies(dir.resolve("applies.jsonl"));
        new Apply(host, MEMU, facts(Map.of()), log).run(List.of(
                new Apply.Change(ConfigField.FPS, new ConfigValue.Count(60), new ConfigValue.Count(30))), new Steps());
        Applies.Entry entry = log.last(InstanceKey.of(MEMU)).orElseThrow();
        host.calls().clear();

        Apply.Outcome undone = Apply.undo(host, MEMU, facts(Map.of()), log, entry, new Steps());

        assertNull(undone.failed());
        assertTrue(host.calls().contains("console memuc.exe setconfigex -n MEmu fps 60"), host.calls().toString());
        assertTrue(log.last(InstanceKey.of(MEMU)).isEmpty());
        assertTrue(Files.readString(dir.resolve("applies.jsonl")).contains("\"undone\":\"" + entry.id() + "\""));
    }

    @Test
    void theLogSkipsALineItCannotRead() throws Exception {
        Path file = dir.resolve("applies.jsonl");
        Applies log = new Applies(file);
        log.record(InstanceKey.of(MEMU), List.of(new Apply.Change(ConfigField.SCREEN,
                new ConfigValue.Screen(1600, 900, 240), new ConfigValue.Screen(900, 1600, 240))));
        Files.writeString(file, Files.readString(file) + "{\"v\":1,\"id\":\"cut\n");

        Applies.Entry entry = log.last(InstanceKey.of(MEMU)).orElseThrow();
        assertEquals(new ConfigValue.Screen(1600, 900, 240), entry.reversed().getFirst().after());
        assertEquals(1, log.all(InstanceKey.of(MEMU)).size());
    }

    private static InstanceFacts facts(Map<ConfigField, ConfigValue> settings) {
        return new InstanceFacts(InstanceKey.of(MEMU), settings, Instant.EPOCH, Optional.empty(), false);
    }

    /** A host whose every action works unless {@link #failing} names it; each call is recorded. */
    private static final class FakeHost implements ApplyHost {
        final Map<String, String> failing = new HashMap<>();
        /** Calls that throw, as an I/O error or a lost device would. */
        final Set<String> throwing = new HashSet<>();
        private final List<String> calls = new ArrayList<>();
        boolean up;

        FakeHost(boolean up) {
            this.up = up;
        }

        List<String> calls() {
            return calls;
        }

        private String call(String name) {
            calls.add(name);
            if (throwing.contains(name)) throw new IllegalStateException(name.split(" ")[1].replace(".exe", "")
                    + " broke");
            return failing.get(name);
        }

        @Override
        public boolean running(EmulatorInstance instance) {
            return up;
        }

        @Override
        public String stop(EmulatorInstance instance) {
            String failed = call("stop");
            if (failed == null) up = false;
            return failed;
        }

        @Override
        public String start(EmulatorInstance instance, Consumer<String> words) {
            String failed = call("start");
            if (failed == null) up = true;
            return failed;
        }

        @Override
        public String console(List<String> command) {
            return call("console " + String.join(" ", command));
        }

        @Override
        public WindowsBackups.Backup keep(EmulatorInstance instance, Path file) {
            call("keep");
            return new WindowsBackups.Backup(file.getParent(), file, file, null);
        }

        @Override
        public String edit(Path file, UnaryOperator<String> change) {
            return call("edit");
        }

        @Override
        public String restore(WindowsBackups.Backup kept) {
            return call("restore");
        }

        @Override
        public String shell(EmulatorInstance instance, String command, boolean root) {
            String name = "shell " + command;
            calls.add(name.startsWith("shell if cmd alarm") ? "shell if cmd alarm" : name);
            return failing.get(name);
        }

        @Override
        public InstanceFacts read(EmulatorInstance instance, InstanceFacts last) {
            call("read");
            return last;
        }
    }

    private static final class Steps implements Operation.Context {
        boolean cancelled;

        @Override
        public void step(String words) {
        }

        @Override
        public void progress(double fraction) {
        }

        @Override
        public void needsYou(String why) {
        }

        @Override
        public void resumed() {
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }
    }
}
