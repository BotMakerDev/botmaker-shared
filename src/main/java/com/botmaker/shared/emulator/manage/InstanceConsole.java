package com.botmaker.shared.emulator.manage;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * A product's own console, as the commands that create, copy, rename and delete its instances and give one new device
 * IDs. Each argument was read from the console's own help on this PC on 2026-11-12: {@code ldconsole} of LDPlayer
 * v14.0.29, {@code MuMuManager} of MuMu Player 12 (Android 12 and 15 engines) and {@code memuc} 1.0.0 of MEmu 9.
 * <p>
 * An instance is selected the way its launch command already selects it: LDPlayer's {@code --index}, MuMu's
 * {@code -v} and MEmu's {@code -n <vm folder>}.
 */
public sealed interface InstanceConsole {

    PlatformId product();

    /** The console's path. */
    String exe();

    /** Creates an instance: named {@code name} when {@link #namesOnCreate()}, else under the product's default name. */
    List<String> create(String name);

    /** Copies {@code from}: named {@code name} when {@link #namesOnCreate()}. */
    List<String> copy(EmulatorInstance from, String name);

    /** Whether {@link #create} and {@link #copy} name the new instance, or a {@link #rename} has to follow. */
    boolean namesOnCreate();

    List<String> rename(EmulatorInstance instance, String name);

    List<String> delete(EmulatorInstance instance);

    /** The commands that give {@code instance} new device IDs, run with it stopped. */
    List<List<String>> newDeviceIds(EmulatorInstance instance, RandomGenerator random);

    /** The console of {@code product}, or null for a product BotMaker drives no console of. */
    static InstanceConsole of(PlatformId product, String exe) {
        return switch (product) {
            case LDPLAYER -> new LdConsole(exe);
            case MUMU -> new MuMuManager(exe);
            case MEMU -> new Memuc(exe);
            default -> null;
        };
    }

    /**
     * What {@code instance}'s launch command passes after {@code flag}: how its console selects it.
     *
     * @throws IllegalStateException when the launch command carries no such selector
     */
    static String selector(EmulatorInstance instance, String flag) {
        List<String> launch = instance.launchCommand();
        int at = launch.indexOf(flag);
        if (at < 0 || at + 1 >= launch.size()) {
            throw new IllegalStateException("BotMaker doesn't know which " + instance.brand() + " instance "
                    + instance.name() + " is: refresh the list and try again.");
        }
        return launch.get(at + 1);
    }

    /** LDPlayer's {@code ldconsole}: {@code add}, {@code copy}, {@code rename}, {@code remove} and {@code modify}. */
    record LdConsole(String exe) implements InstanceConsole {

        @Override
        public PlatformId product() {
            return PlatformId.LDPLAYER;
        }

        @Override
        public List<String> create(String name) {
            return List.of(exe, "add", "--name", name);
        }

        @Override
        public List<String> copy(EmulatorInstance from, String name) {
            return List.of(exe, "copy", "--name", name, "--from", index(from));
        }

        @Override
        public boolean namesOnCreate() {
            return true;
        }

        @Override
        public List<String> rename(EmulatorInstance instance, String name) {
            return List.of(exe, "rename", "--index", index(instance), "--title", name);
        }

        @Override
        public List<String> delete(EmulatorInstance instance) {
            return List.of(exe, "remove", "--index", index(instance));
        }

        /** {@code modify}'s {@code auto} draws each ID itself. */
        @Override
        public List<List<String>> newDeviceIds(EmulatorInstance instance, RandomGenerator random) {
            return List.of(List.of(exe, "modify", "--index", index(instance), "--imei", "auto", "--imsi", "auto",
                    "--simserial", "auto", "--androidid", "auto", "--mac", "auto"));
        }

        private static String index(EmulatorInstance instance) {
            return selector(instance, "--index");
        }
    }

    /**
     * MuMu's {@code MuMuManager}: {@code create}, {@code clone}, {@code rename}, {@code delete} and {@code simulation}.
     * Its {@code simulation} takes a value and draws none, so BotMaker draws them ({@link DeviceIds}).
     */
    record MuMuManager(String exe) implements InstanceConsole {

        @Override
        public PlatformId product() {
            return PlatformId.MUMU;
        }

        @Override
        public List<String> create(String name) {
            return List.of(exe, "create", "-n", "1");
        }

        @Override
        public List<String> copy(EmulatorInstance from, String name) {
            return List.of(exe, "clone", "-v", index(from));
        }

        @Override
        public boolean namesOnCreate() {
            return false;
        }

        @Override
        public List<String> rename(EmulatorInstance instance, String name) {
            return List.of(exe, "rename", "-v", index(instance), "-n", name);
        }

        @Override
        public List<String> delete(EmulatorInstance instance) {
            return List.of(exe, "delete", "-v", index(instance));
        }

        @Override
        public List<List<String>> newDeviceIds(EmulatorInstance instance, RandomGenerator random) {
            String index = index(instance);
            return List.of(
                    List.of(exe, "simulation", "-v", index, "-sk", "android_id", "-sv", DeviceIds.androidId(random)),
                    List.of(exe, "simulation", "-v", index, "-sk", "mac_address", "-sv", DeviceIds.mac(random)),
                    List.of(exe, "simulation", "-v", index, "-sk", "imei", "-sv", DeviceIds.imei(random)));
        }

        private static String index(EmulatorInstance instance) {
            return selector(instance, "-v");
        }
    }

    /** MEmu's {@code memuc}: {@code create}, {@code clone}, {@code rename}, {@code remove} and {@code randomize}. */
    record Memuc(String exe) implements InstanceConsole {

        @Override
        public PlatformId product() {
            return PlatformId.MEMU;
        }

        /** Android 9 (64-bit): without a version memuc creates its oldest. */
        @Override
        public List<String> create(String name) {
            return List.of(exe, "create", "96");
        }

        @Override
        public List<String> copy(EmulatorInstance from, String name) {
            return List.of(exe, "clone", "-n", vm(from));
        }

        @Override
        public boolean namesOnCreate() {
            return false;
        }

        @Override
        public List<String> rename(EmulatorInstance instance, String name) {
            return List.of(exe, "rename", "-n", vm(instance), name);
        }

        @Override
        public List<String> delete(EmulatorInstance instance) {
            return List.of(exe, "remove", "-n", vm(instance));
        }

        @Override
        public List<List<String>> newDeviceIds(EmulatorInstance instance, RandomGenerator random) {
            return List.of(List.of(exe, "randomize", "-n", vm(instance)));
        }

        private static String vm(EmulatorInstance instance) {
            return selector(instance, "-n");
        }
    }
}
