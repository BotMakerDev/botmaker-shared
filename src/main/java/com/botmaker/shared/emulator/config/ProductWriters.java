package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.QuietRun;
import com.botmaker.shared.emulator.checks.WindowsSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static com.botmaker.shared.emulator.config.ConfigField.ADB;
import static com.botmaker.shared.emulator.config.ConfigField.CPU_CORES;
import static com.botmaker.shared.emulator.config.ConfigField.FPS;
import static com.botmaker.shared.emulator.config.ConfigField.LOCALE;
import static com.botmaker.shared.emulator.config.ConfigField.MEMORY;
import static com.botmaker.shared.emulator.config.ConfigField.POPUPS_OFF;
import static com.botmaker.shared.emulator.config.ConfigField.ROOT;
import static com.botmaker.shared.emulator.config.ConfigField.SCREEN;
import static com.botmaker.shared.emulator.config.ConfigField.TIME_ZONE;

/**
 * What Apply writes, per product, to give an instance its wanted values: the console first, then the settings file,
 * then Android over ADB. Pure: it reads and runs nothing, so each product's writes are tested over the commands they
 * say. Every console argument was checked against that console's help on this PC (2026-11-12): LDPlayer 14's
 * {@code ldconsole modify}, MuMu 12's {@code MuMuManager setting --info}, MEmu 9's {@code memuc getconfigex}.
 */
public final class ProductWriters {

    private ProductWriters() {}

    /**
     * The writes, in the order they run.
     *
     * @param stopped written with the instance stopped (console and file)
     * @param running run on Android once it is up
     * @param refused a wanted value this product cannot take, and why, in the user's words; nothing is written for it
     */
    public record Plan(List<SettingWrite> stopped, List<SettingWrite> running, Map<ConfigField, String> refused) {
        public Plan {
            stopped = List.copyOf(stopped);
            running = List.copyOf(running);
            Map<ConfigField, String> copy = new EnumMap<>(ConfigField.class);
            copy.putAll(refused);
            refused = Collections.unmodifiableMap(copy);
        }

        public boolean isEmpty() {
            return stopped.isEmpty() && running.isEmpty();
        }
    }

    /** LDPlayer's {@code modify --memory} takes only these. */
    static final Set<Integer> LDPLAYER_MEMORY = Set.of(256, 512, 768, 1024, 1536, 2048, 4096, 8192);

    /**
     * The writes that give {@code instance} {@code wanted}.
     *
     * @param now what the instance has, for the values a product writes together (MuMu's custom CPU and memory) and
     *            for root, which a locale needs
     */
    public static Plan of(EmulatorInstance instance, Map<ConfigField, ConfigValue> wanted,
                          Map<ConfigField, ConfigValue> now) {
        return of(instance, wanted, now, WindowsSettings::fileOf);
    }

    /** As {@link #of(EmulatorInstance, Map, Map)}, the settings file found by {@code files}; for tests. */
    static Plan of(EmulatorInstance instance, Map<ConfigField, ConfigValue> wanted, Map<ConfigField, ConfigValue> now,
                   Function<EmulatorInstance, Optional<Path>> files) {
        ProductSettings product = ProductSettings.of(instance.platformId());
        List<SettingWrite> stopped = new ArrayList<>();
        List<SettingWrite> running = new ArrayList<>();
        Map<ConfigField, String> refused = new EnumMap<>(ConfigField.class);
        Map<ConfigField, ConfigValue> own = new EnumMap<>(ConfigField.class);
        wanted.forEach((field, value) -> {
            if (!product.applies(field)) {
                refused.put(field, "BotMaker can't set this on " + instance.brand() + ": set it by hand.");
            } else if (ProductSettings.overAdb(field)) {
                overAdb(field, value, wanted, now, running, refused);
            } else {
                own.put(field, value);
            }
        });
        if (!own.isEmpty()) {
            switch (product) {
                case LDPLAYER -> ldPlayer(instance, own, files.apply(instance), stopped, refused);
                case BLUESTACKS -> blueStacks(instance, own, files.apply(instance), stopped, refused);
                case MUMU -> muMu(instance, own, now, stopped, refused);
                case MEMU -> memu(instance, own, stopped, refused);
                default -> own.keySet().forEach(f -> refused.put(f, "BotMaker can't set this on " + instance.brand()
                        + " yet."));
            }
        }
        if ((product == ProductSettings.MUMU || product == ProductSettings.MEMU) && own.containsKey(SCREEN)
                && !refused.containsKey(SCREEN)) {
            // MuMu and MEmu were found with a wm override over their own screen: apps see the override, so clear it
            running.addFirst(new SettingWrite.Shell(List.of("wm size reset", "wm density reset"), false, false,
                    "Clearing Android's own screen override"));
        }
        return new Plan(stopped, running, refused);
    }

    // --- Android, over ADB ---

    private static void overAdb(ConfigField field, ConfigValue value, Map<ConfigField, ConfigValue> wanted,
                                Map<ConfigField, ConfigValue> now, List<SettingWrite> running,
                                Map<ConfigField, String> refused) {
        switch (field) {
            case LOCALE -> {
                String tag = text(value);
                ConfigValue root = wanted.getOrDefault(ROOT, now.get(ROOT));
                if (tag == null || !tag.matches("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*")) {
                    refused.put(field, "\"" + tag + "\" is not a locale such as en-US.");
                } else if (!(root instanceof ConfigValue.Toggle(boolean on) && on)) {
                    refused.put(field, "Android's language needs root on this instance: turn Root on, or set it in "
                            + "Android's Settings.");
                } else {
                    running.add(new SettingWrite.Shell(List.of("setprop persist.sys.locale " + tag.replace('_', '-')),
                            true, true, "Setting the language to " + tag + " (seen after the next start)"));
                }
            }
            case TIME_ZONE -> {
                String zone = text(value);
                if (zone == null || !zone.matches("[A-Za-z0-9_+\\-]+(/[A-Za-z0-9_+\\-]+)*")) {
                    refused.put(field, "\"" + zone + "\" is not a time zone such as Europe/Paris.");
                } else {
                    // Android 11 and later say set-timezone in cmd alarm's help; before, IAlarmManager's call 3 is it
                    running.add(new SettingWrite.Shell(List.of("settings put global auto_time_zone 0",
                            "if cmd alarm help 2>&1 | grep -q set-timezone; then cmd alarm set-timezone " + zone
                                    + "; else service call alarm 3 s16 " + zone + "; fi"),
                            false, true, "Setting the time zone to " + zone));
                }
            }
            // best effort, as QuietRun runs them: an image without the Google app refuses its line
            case POPUPS_OFF -> running.add(on(value)
                    ? new SettingWrite.Shell(QuietRun.calmCommands(), false, false, "Turning popups and Gemini off")
                    : new SettingWrite.Shell(QuietRun.uncalmCommands(), false, false,
                    "Turning popups and Gemini back on"));
            default -> refused.put(field, "BotMaker can't set this over ADB.");
        }
    }

    // --- LDPlayer: ldconsole modify, and its file for FPS and ADB ---

    private static void ldPlayer(EmulatorInstance instance, Map<ConfigField, ConfigValue> own, Optional<Path> file,
                                 List<SettingWrite> stopped, Map<ConfigField, String> refused) {
        List<String> launch = instance.launchCommand();
        Optional<String> index = after(launch, "--index");
        List<String> modify = new ArrayList<>();
        List<String> said = new ArrayList<>();
        Set<ConfigField> modified = EnumSet.noneOf(ConfigField.class);
        own.forEach((field, value) -> {
            if (field != FPS && field != ADB) modified.add(field);
            switch (field) {
                case SCREEN -> {
                    ConfigValue.Screen s = (ConfigValue.Screen) value;
                    modify.addAll(List.of("--resolution", s.width() + "," + s.height() + "," + s.dpi()));
                    said.add("screen " + s.width() + "×" + s.height() + " at " + s.dpi() + " dpi");
                }
                case CPU_CORES -> {
                    int cores = count(value);
                    if (cores < 1 || cores > 4) {
                        refused.put(field, "LDPlayer takes 1 to 4 CPU cores.");
                    } else {
                        modify.addAll(List.of("--cpu", String.valueOf(cores)));
                        said.add(cores + " CPU cores");
                    }
                }
                case MEMORY -> {
                    int mb = count(value);
                    if (!LDPLAYER_MEMORY.contains(mb)) {
                        refused.put(field, "LDPlayer takes 256, 512, 768, 1024, 1536, 2048, 4096 or 8192 MB.");
                    } else {
                        modify.addAll(List.of("--memory", String.valueOf(mb)));
                        said.add(mb + " MB of memory");
                    }
                }
                case ROOT -> {
                    modify.addAll(List.of("--root", on(value) ? "1" : "0"));
                    said.add("root " + (on(value) ? "on" : "off"));
                }
                default -> {
                }
            }
        });
        if (!modify.isEmpty()) {
            if (index.isEmpty()) {
                own.keySet().stream().filter(f -> f != FPS && f != ADB && !refused.containsKey(f))
                        .forEach(f -> refused.put(f, "BotMaker can't find LDPlayer's console for this instance."));
            } else {
                List<String> command = new ArrayList<>(List.of(launch.getFirst(), "modify", "--index", index.get()));
                command.addAll(modify);
                modified.removeAll(refused.keySet());
                stopped.add(new SettingWrite.Console(command, modified, "Setting " + String.join(", ", said)));
            }
        }
        if (own.containsKey(FPS)) {
            int fps = count(own.get(FPS));
            if (fps < 1 || fps > 60) refused.put(FPS, "LDPlayer takes 1 to 60 frames a second.");
            else fileEdit(file, Set.of(FPS), text -> WindowsSettings.ldPlayerWith(text, "basicSettings.fps",
                    String.valueOf(fps)), "Setting " + fps + " frames a second", stopped, refused);
        }
        if (own.containsKey(ADB)) {
            boolean adb = on(own.get(ADB));
            fileEdit(file, Set.of(ADB), text -> WindowsSettings.ldPlayerWithAdb(text, adb),
                    "Turning ADB " + (adb ? "on" : "off"), stopped, refused);
        }
    }

    // --- BlueStacks and MSI: bluestacks.conf ---

    private static void blueStacks(EmulatorInstance instance, Map<ConfigField, ConfigValue> own, Optional<Path> file,
                                   List<SettingWrite> stopped, Map<ConfigField, String> refused) {
        String token = WindowsSettings.token(instance);
        if (token == null) {
            own.keySet().forEach(f -> refused.put(f, "BotMaker can't tell which BlueStacks instance this is."));
            return;
        }
        String prefix = "bst.instance." + token + ".";
        List<UnaryOperator<String>> edits = new ArrayList<>();
        List<String> said = new ArrayList<>();
        own.forEach((field, value) -> {
            switch (field) {
                case SCREEN -> {
                    ConfigValue.Screen s = (ConfigValue.Screen) value;
                    edits.add(conf(prefix + "fb_width", s.width()));
                    edits.add(conf(prefix + "fb_height", s.height()));
                    edits.add(conf(prefix + "dpi", s.dpi()));
                    said.add("screen " + s.width() + "×" + s.height() + " at " + s.dpi() + " dpi");
                }
                case CPU_CORES -> {
                    edits.add(conf(prefix + "cpus", count(value)));
                    said.add(count(value) + " CPU cores");
                }
                case MEMORY -> {
                    edits.add(conf(prefix + "ram", count(value)));
                    said.add(count(value) + " MB of memory");
                }
                case FPS -> {
                    edits.add(conf(prefix + "max_fps", count(value)));
                    said.add(count(value) + " frames a second");
                }
                case ROOT -> {
                    edits.add(conf(prefix + "enable_root_access", on(value) ? 1 : 0));
                    said.add("root " + (on(value) ? "on" : "off"));
                }
                case ADB -> {
                    // one switch for every instance of the edition
                    edits.add(conf("bst.enable_adb_access", on(value) ? 1 : 0));
                    said.add("ADB " + (on(value) ? "on" : "off") + " (every instance of this BlueStacks)");
                }
                default -> {
                }
            }
        });
        UnaryOperator<String> all = text -> {
            String out = text;
            for (UnaryOperator<String> edit : edits) out = edit.apply(out);
            return out;
        };
        fileEdit(file, own.keySet(), all, "Setting " + String.join(", ", said), stopped,
                refused);
    }

    private static UnaryOperator<String> conf(String key, int value) {
        return text -> WindowsSettings.blueStacksWith(text, key, String.valueOf(value));
    }

    // --- MuMu: MuMuManager setting ---

    private static void muMu(EmulatorInstance instance, Map<ConfigField, ConfigValue> own,
                             Map<ConfigField, ConfigValue> now, List<SettingWrite> stopped,
                             Map<ConfigField, String> refused) {
        List<String> launch = instance.launchCommand();
        Optional<String> index = after(launch, "-v");
        if (index.isEmpty()) {
            own.keySet().forEach(f -> refused.put(f, "BotMaker can't find MuMu's console for this instance."));
            return;
        }
        List<String> keys = new ArrayList<>();
        List<String> said = new ArrayList<>();
        Set<ConfigField> written = EnumSet.noneOf(ConfigField.class);
        if (own.get(SCREEN) instanceof ConfigValue.Screen s) {
            written.add(SCREEN);
            // MuMu keeps the screen turned against Android's (ProductSettings.turnsScreen)
            keys.addAll(List.of("-k", "resolution_mode", "-val", "custom",
                    "-k", "resolution_width.custom", "-val", String.valueOf(s.height()),
                    "-k", "resolution_height.custom", "-val", String.valueOf(s.width()),
                    "-k", "resolution_dpi.custom", "-val", String.valueOf(s.dpi())));
            said.add("screen " + s.width() + "×" + s.height() + " at " + s.dpi() + " dpi");
        }
        if (own.containsKey(CPU_CORES) || own.containsKey(MEMORY)) {
            // a custom performance takes both: the one not changed keeps what the instance has now
            ConfigValue cpu = own.getOrDefault(CPU_CORES, now.get(CPU_CORES));
            ConfigValue memory = own.getOrDefault(MEMORY, now.get(MEMORY));
            if (cpu == null || memory == null) {
                for (ConfigField f : List.of(CPU_CORES, MEMORY)) {
                    if (own.containsKey(f)) refused.put(f, "MuMu sets CPU and memory together, and BotMaker could "
                            + "not read the other one: refresh, then Apply again.");
                }
            } else {
                keys.addAll(List.of("-k", "performance_mode", "-val", "custom",
                        "-k", "performance_cpu.custom", "-val", String.valueOf(count(cpu)),
                        "-k", "performance_mem.custom", "-val", gigabytes(count(memory))));
                said.add(count(cpu) + " CPU cores, " + count(memory) + " MB of memory");
                written.addAll(List.of(CPU_CORES, MEMORY));
            }
        }
        if (own.containsKey(FPS)) {
            written.add(FPS);
            keys.addAll(List.of("-k", "max_frame_rate", "-val", String.valueOf(count(own.get(FPS)))));
            said.add(count(own.get(FPS)) + " frames a second");
        }
        if (own.containsKey(ROOT)) {
            written.add(ROOT);
            keys.addAll(List.of("-k", "root_permission", "-val", on(own.get(ROOT)) ? "true" : "false"));
            said.add("root " + (on(own.get(ROOT)) ? "on" : "off"));
        }
        if (keys.isEmpty()) return;
        List<String> command = new ArrayList<>(List.of(launch.getFirst(), "setting", "-v", index.get()));
        command.addAll(keys);
        stopped.add(new SettingWrite.Console(command, written, "Setting " + String.join(", ", said)));
    }

    /** MuMu's memory is in GB, written as it writes it: {@code 4.000000}. */
    static String gigabytes(int mb) {
        return String.format(Locale.ROOT, "%.6f", mb / 1024.0);
    }

    // --- MEmu: memuc setconfigex, one key a command ---

    private static void memu(EmulatorInstance instance, Map<ConfigField, ConfigValue> own,
                             List<SettingWrite> stopped, Map<ConfigField, String> refused) {
        List<String> launch = instance.launchCommand();
        Optional<String> vm = after(launch, "-n");
        if (launch.isEmpty() || vm.isEmpty()) {
            own.keySet().forEach(f -> refused.put(f, "BotMaker can't find MEmu's console for this instance."));
            return;
        }
        String memuc = launch.getFirst(); // MEmu's instances start through memuc itself
        own.forEach((field, value) -> {
            switch (field) {
                case SCREEN -> {
                    ConfigValue.Screen s = (ConfigValue.Screen) value;
                    String screen = s.width() + "×" + s.height() + " at " + s.dpi() + " dpi";
                    stopped.add(memuc(memuc, vm.get(), field, "is_customed_resolution", "1",
                            "Setting a custom screen for " + screen));
                    stopped.add(memuc(memuc, vm.get(), field, "resolution_width", String.valueOf(s.width()),
                            "Setting the screen's width to " + s.width()));
                    stopped.add(memuc(memuc, vm.get(), field, "resolution_height", String.valueOf(s.height()),
                            "Setting the screen's height to " + s.height()));
                    stopped.add(memuc(memuc, vm.get(), field, "vbox_dpi", String.valueOf(s.dpi()),
                            "Setting the screen's density to " + s.dpi() + " dpi"));
                }
                case CPU_CORES -> stopped.add(memuc(memuc, vm.get(), field, "cpus", String.valueOf(count(value)),
                        "Setting " + count(value) + " CPU cores"));
                case MEMORY -> stopped.add(memuc(memuc, vm.get(), field, "memory", String.valueOf(count(value)),
                        "Setting " + count(value) + " MB of memory"));
                case FPS -> stopped.add(memuc(memuc, vm.get(), field, "fps", String.valueOf(count(value)),
                        "Setting " + count(value) + " frames a second"));
                case ROOT -> stopped.add(memuc(memuc, vm.get(), field, "enable_su", on(value) ? "1" : "0",
                        "Turning root " + (on(value) ? "on" : "off")));
                default -> refused.put(field, "BotMaker can't set this on MEmu.");
            }
        });
    }

    private static SettingWrite memuc(String memuc, String vm, ConfigField field, String key, String value,
                                      String what) {
        return new SettingWrite.Console(List.of(memuc, "setconfigex", "-n", vm, key, value), Set.of(field), what);
    }

    // --- helpers ---

    private static void fileEdit(Optional<Path> file, Set<ConfigField> fields, UnaryOperator<String> edit,
                                 String what, List<SettingWrite> stopped, Map<ConfigField, String> refused) {
        if (file.isEmpty()) {
            fields.forEach(f -> refused.put(f, "BotMaker can't find this instance's settings file."));
        } else {
            stopped.add(new SettingWrite.FileEdit(file.get(), edit, what));
        }
    }

    private static Optional<String> after(List<String> command, String flag) {
        int at = command.indexOf(flag);
        return at < 0 || at + 1 >= command.size() ? Optional.empty() : Optional.of(command.get(at + 1));
    }

    private static int count(ConfigValue value) {
        return switch (value) {
            case ConfigValue.Count c -> c.value();
            case ConfigValue.Megabytes m -> m.value();
            default -> -1;
        };
    }

    private static boolean on(ConfigValue value) {
        return value instanceof ConfigValue.Toggle(boolean on) && on;
    }

    private static String text(ConfigValue value) {
        return value instanceof ConfigValue.Text(String text) ? text.strip() : null;
    }
}
