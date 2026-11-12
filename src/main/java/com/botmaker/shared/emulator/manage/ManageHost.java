package com.botmaker.shared.emulator.manage;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorPlatform;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.config.AndroidState;
import com.botmaker.shared.emulator.checks.SettingsFiles;
import com.botmaker.shared.emulator.checks.WindowsSettings;
import com.botmaker.shared.emulator.config.ApplyHost;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What {@link InstanceManager} does to the world: list a product's instances, run its console, stop and start an
 * instance, and read the Google accounts on it. {@link #live()} is this PC's; tests give their own. stop and start
 * answer null when they worked, else why not in the user's words.
 */
public interface ManageHost {

    List<EmulatorInstance> instances(PlatformId product) throws Exception;

    /** The console's exit and output, or null when it did not answer in time. */
    Spawn.Completed console(List<String> command) throws Exception;

    boolean running(EmulatorInstance instance);

    String stop(EmulatorInstance instance) throws Exception;

    String start(EmulatorInstance instance, Consumer<String> words) throws Exception;

    /** The Google emails signed in on the running {@code instance}, or null when Android did not say. */
    Set<String> googleAccounts(EmulatorInstance instance) throws Exception;

    /**
     * Turns ADB on in the stopped {@code instance}'s settings where its product keeps it off (LDPlayer, and a clone
     * of an instance that had it off); null when it is on, else why not.
     */
    String adbOn(EmulatorInstance instance) throws Exception;

    static ManageHost live() {
        return Live.INSTANCE;
    }

    /** This PC's emulators. */
    final class Live implements ManageHost {

        static final Live INSTANCE = new Live();

        /** A console copies a disk image in a create or a clone: minutes, not seconds. */
        private static final Duration CONSOLE_TIMEOUT = Duration.ofMinutes(5);

        private Live() {}

        @Override
        public List<EmulatorInstance> instances(PlatformId product) {
            for (EmulatorPlatform platform : Platforms.ALL) {
                if (platform.id() == product) return platform.discover();
            }
            return List.of();
        }

        @Override
        public Spawn.Completed console(List<String> command) throws Exception {
            return Spawn.run(CONSOLE_TIMEOUT, command);
        }

        @Override
        public boolean running(EmulatorInstance instance) {
            return ApplyHost.live().running(instance);
        }

        @Override
        public String stop(EmulatorInstance instance) throws Exception {
            return ApplyHost.live().stop(instance);
        }

        @Override
        public String start(EmulatorInstance instance, Consumer<String> words) throws Exception {
            return ApplyHost.live().start(instance, words);
        }

        @Override
        public Set<String> googleAccounts(EmulatorInstance instance) {
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                return AndroidState.googleAccounts(device.shell(AndroidState.ACCOUNTS));
            }
        }

        /** LDPlayer only: MuMu and MEmu have ADB on with no switch, and BlueStacks is managed in its own manager. */
        @Override
        public String adbOn(EmulatorInstance instance) throws Exception {
            if (instance.platformId() != PlatformId.LDPLAYER) return null;
            Optional<Path> file = WindowsSettings.fileOf(instance);
            if (file.isEmpty()) return "BotMaker didn't find " + instance.name() + "'s settings file to turn ADB on.";
            if (Boolean.TRUE.equals(WindowsSettings.of(instance).adbOn())) return null;
            return SettingsFiles.edit(file.get(), text -> WindowsSettings.ldPlayerWithAdb(text, true));
        }
    }
}
