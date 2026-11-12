package com.botmaker.shared.emulator.config;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorLiveness;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.EmulatorState;
import com.botmaker.shared.emulator.checks.SettingsFiles;
import com.botmaker.shared.emulator.checks.WindowsBackups;
import com.botmaker.shared.emulator.checks.WindowsFixes;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an {@link Apply} does to the world: stop and start an instance, run its console, keep and edit its file, and
 * run commands on its Android. {@link #live()} is this PC's; tests give their own. Each action answers null when it
 * worked, else why not in the user's words.
 */
public interface ApplyHost {

    boolean running(EmulatorInstance instance);

    String stop(EmulatorInstance instance) throws Exception;

    String start(EmulatorInstance instance, Consumer<String> words) throws Exception;

    String console(List<String> command) throws Exception;

    WindowsBackups.Backup keep(EmulatorInstance instance, Path file) throws Exception;

    String edit(Path file, UnaryOperator<String> change) throws Exception;

    String restore(WindowsBackups.Backup kept) throws Exception;

    String shell(EmulatorInstance instance, String command, boolean root) throws Exception;

    /** What the instance has now, {@code last}'s Android kept when it is stopped. */
    InstanceFacts read(EmulatorInstance instance, InstanceFacts last);

    static ApplyHost live() {
        return Live.INSTANCE;
    }

    /** This PC's emulators. */
    final class Live implements ApplyHost {

        static final Live INSTANCE = new Live();

        private static final Duration CONSOLE_TIMEOUT = Duration.ofMinutes(2);
        private static final Pattern EXIT = Pattern.compile("__rc=(\\d+)\\s*$");

        private Live() {}

        @Override
        public boolean running(EmulatorInstance instance) {
            return EmulatorLiveness.check(instance).state() != EmulatorState.STOPPED;
        }

        @Override
        public String stop(EmulatorInstance instance) throws InterruptedException {
            return WindowsFixes.stopToChange(instance);
        }

        @Override
        public String start(EmulatorInstance instance, Consumer<String> words) {
            if (!EmulatorLauncher.launch(instance)) return instance.brand() + "'s start command did not run.";
            return EmulatorReadiness.bringUp(instance, words).isPresent() ? null : EmulatorReadiness.notReady(instance);
        }

        @Override
        public String console(List<String> command) throws Exception {
            Spawn.Completed done = Spawn.run(CONSOLE_TIMEOUT, command);
            String program = Path.of(command.getFirst()).getFileName().toString();
            if (done == null) return program + " did not answer.";
            return done.ok() ? null : program + " refused the change"
                    + (done.output().isBlank() ? "." : ": " + done.output().strip());
        }

        @Override
        public WindowsBackups.Backup keep(EmulatorInstance instance, Path file) throws Exception {
            return SettingsFiles.keep(instance, file);
        }

        @Override
        public String edit(Path file, UnaryOperator<String> change) throws Exception {
            return SettingsFiles.edit(file, change);
        }

        @Override
        public String restore(WindowsBackups.Backup kept) throws Exception {
            return SettingsFiles.restore(kept);
        }

        @Override
        public String shell(EmulatorInstance instance, String command, boolean root) {
            String line = (root ? "su -c '" + command.replace("'", "'\\''") + "'" : command) + "; echo __rc=$?";
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                String out = device.shell(line);
                Matcher m = EXIT.matcher(out == null ? "" : out.strip());
                if (m.find() && m.group(1).equals("0")) return null;
                String said = out == null ? "" : EXIT.matcher(out.strip()).replaceAll("").strip();
                return "Android refused \"" + command + "\"" + (said.isEmpty() ? "." : ": " + said);
            }
        }

        @Override
        public InstanceFacts read(EmulatorInstance instance, InstanceFacts last) {
            if (!running(instance)) return InstanceFacts.read(instance, null, last, Clock.systemUTC());
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                return InstanceFacts.read(instance, device, last, Clock.systemUTC());
            }
        }
    }
}
