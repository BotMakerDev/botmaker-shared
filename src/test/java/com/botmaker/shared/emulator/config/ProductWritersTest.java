package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.QuietRun;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.botmaker.shared.emulator.config.ConfigField.ADB;
import static com.botmaker.shared.emulator.config.ConfigField.CPU_CORES;
import static com.botmaker.shared.emulator.config.ConfigField.FPS;
import static com.botmaker.shared.emulator.config.ConfigField.LOCALE;
import static com.botmaker.shared.emulator.config.ConfigField.MEMORY;
import static com.botmaker.shared.emulator.config.ConfigField.POPUPS_OFF;
import static com.botmaker.shared.emulator.config.ConfigField.ROOT;
import static com.botmaker.shared.emulator.config.ConfigField.SCREEN;
import static com.botmaker.shared.emulator.config.ConfigField.TIME_ZONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each product's writes, as the commands and edits they say; nothing runs. */
class ProductWritersTest {

    private static final Path FILE = Path.of("C:/fake/settings");

    @Test
    void muMuTurnsTheScreenAndWritesCpuAndMemoryTogether() {
        EmulatorInstance mumu = instance(PlatformId.MUMU, "C:/MuMu/MuMuManager.exe", "control", "-v", "0", "launch");
        ProductWriters.Plan plan = ProductWriters.of(mumu,
                Map.of(SCREEN, new ConfigValue.Screen(900, 1600, 240), MEMORY, new ConfigValue.Megabytes(6144),
                        ROOT, new ConfigValue.Toggle(true)),
                Map.of(CPU_CORES, new ConfigValue.Count(4), MEMORY, new ConfigValue.Megabytes(4096)), none());

        assertEquals(1, plan.stopped().size(), "one MuMuManager call for every key");
        List<String> command = ((SettingWrite.Console) plan.stopped().getFirst()).command();
        assertEquals(List.of("C:/MuMu/MuMuManager.exe", "setting", "-v", "0"), command.subList(0, 4));
        assertPair(command, "resolution_mode", "custom");
        assertPair(command, "resolution_width.custom", "1600"); // Android's 900x1600 is MuMu's 1600x900
        assertPair(command, "resolution_height.custom", "900");
        assertPair(command, "resolution_dpi.custom", "240");
        assertPair(command, "performance_mode", "custom");
        assertPair(command, "performance_cpu.custom", "4");
        assertPair(command, "performance_mem.custom", "6.000000");
        assertPair(command, "root_permission", "true");
        assertEquals(new SettingWrite.Shell(List.of("wm size reset", "wm density reset"), false, false,
                "Clearing Android's own screen override"), plan.running().getFirst());
    }

    @Test
    void muMuRefusesMemoryWhenItsCpuWasNeverRead() {
        EmulatorInstance mumu = instance(PlatformId.MUMU, "C:/MuMu/MuMuManager.exe", "control", "-v", "1", "launch");
        ProductWriters.Plan plan = ProductWriters.of(mumu, Map.of(MEMORY, new ConfigValue.Megabytes(4096)), Map.of(),
                none());
        assertTrue(plan.isEmpty());
        assertTrue(plan.refused().containsKey(MEMORY));
    }

    @Test
    void memuWritesOneKeyACommandThroughMemuc() {
        EmulatorInstance memu = instance(PlatformId.MEMU, "C:/Microvirt/MEmu/memuc.exe", "start", "-n", "MEmu");
        ProductWriters.Plan plan = ProductWriters.of(memu, ordered(
                SCREEN, new ConfigValue.Screen(900, 1600, 320),
                CPU_CORES, new ConfigValue.Count(4),
                MEMORY, new ConfigValue.Megabytes(4096),
                FPS, new ConfigValue.Count(30),
                ROOT, new ConfigValue.Toggle(false)), Map.of(), none());

        List<List<String>> commands = plan.stopped().stream()
                .map(w -> ((SettingWrite.Console) w).command().subList(1, 6)).toList();
        assertEquals(List.of(
                List.of("setconfigex", "-n", "MEmu", "is_customed_resolution", "1"),
                List.of("setconfigex", "-n", "MEmu", "resolution_width", "900"),
                List.of("setconfigex", "-n", "MEmu", "resolution_height", "1600"),
                List.of("setconfigex", "-n", "MEmu", "vbox_dpi", "320"),
                List.of("setconfigex", "-n", "MEmu", "cpus", "4"),
                List.of("setconfigex", "-n", "MEmu", "memory", "4096"),
                List.of("setconfigex", "-n", "MEmu", "fps", "30"),
                List.of("setconfigex", "-n", "MEmu", "enable_su", "0")), commands);
        assertEquals("C:/Microvirt/MEmu/memuc.exe", ((SettingWrite.Console) plan.stopped().getFirst()).command()
                .getFirst());
    }

    @Test
    void ldPlayerModifiesThroughItsConsoleAndItsFileForFpsAndAdb() throws IOException {
        EmulatorInstance ld = instance(PlatformId.LDPLAYER, "C:/LDPlayer/ldconsole.exe", "launch", "--index", "2");
        ProductWriters.Plan plan = ProductWriters.of(ld, ordered(
                SCREEN, new ConfigValue.Screen(900, 1600, 240),
                CPU_CORES, new ConfigValue.Count(4),
                MEMORY, new ConfigValue.Megabytes(4096),
                FPS, new ConfigValue.Count(30),
                ADB, new ConfigValue.Toggle(true)), Map.of(), file());

        assertEquals(List.of("C:/LDPlayer/ldconsole.exe", "modify", "--index", "2", "--resolution", "900,1600,240",
                "--cpu", "4", "--memory", "4096"), ((SettingWrite.Console) plan.stopped().getFirst()).command());
        String config = InstanceFactsTest.resource("ldplayer/leidian0.config");
        String edited = config;
        for (SettingWrite write : plan.stopped().subList(1, 3)) {
            edited = ((SettingWrite.FileEdit) write).edit().apply(edited);
        }
        Map<ConfigField, ConfigValue> read = ProductSettings.LDPLAYER.parse(ld, edited);
        assertEquals(new ConfigValue.Count(30), read.get(FPS));
        assertEquals(new ConfigValue.Toggle(true), read.get(ADB));
    }

    @Test
    void ldPlayerRefusesWhatItsConsoleWillNotTake() {
        EmulatorInstance ld = instance(PlatformId.LDPLAYER, "C:/LDPlayer/ldconsole.exe", "launch", "--index", "0");
        ProductWriters.Plan plan = ProductWriters.of(ld, Map.of(CPU_CORES, new ConfigValue.Count(6),
                MEMORY, new ConfigValue.Megabytes(3000)), Map.of(), file());
        assertTrue(plan.isEmpty());
        assertEquals(Set.of(CPU_CORES, MEMORY), plan.refused().keySet());
    }

    @Test
    void blueStacksEditsItsOwnInstancesLines() throws IOException {
        EmulatorInstance bs = instance(PlatformId.BLUESTACKS, "HD-Player.exe", "--instance", "Pie64");
        ProductWriters.Plan plan = ProductWriters.of(bs, ordered(
                SCREEN, new ConfigValue.Screen(900, 1600, 320),
                CPU_CORES, new ConfigValue.Count(2),
                MEMORY, new ConfigValue.Megabytes(3072),
                FPS, new ConfigValue.Count(30),
                ROOT, new ConfigValue.Toggle(true)), Map.of(), file());

        assertEquals(1, plan.stopped().size(), "one edit of bluestacks.conf");
        String edited = ((SettingWrite.FileEdit) plan.stopped().getFirst()).edit()
                .apply(InstanceFactsTest.resource("bluestacks/bluestacks.conf"));
        Map<ConfigField, ConfigValue> read = ProductSettings.BLUESTACKS.parse(bs, edited);
        assertEquals(new ConfigValue.Screen(900, 1600, 320), read.get(SCREEN));
        assertEquals(new ConfigValue.Count(2), read.get(CPU_CORES));
        assertEquals(new ConfigValue.Megabytes(3072), read.get(MEMORY));
        assertEquals(new ConfigValue.Count(30), read.get(FPS));
        assertEquals(new ConfigValue.Toggle(true), read.get(ROOT));

        ProductWriters.Plan noToken = ProductWriters.of(instance(PlatformId.BLUESTACKS, "HD-Player.exe"),
                Map.of(FPS, new ConfigValue.Count(30)), Map.of(), file());
        assertTrue(noToken.isEmpty());
        assertTrue(noToken.refused().containsKey(FPS));
    }

    @Test
    void androidsFieldsGoOverAdbAndALocaleNeedsRoot() {
        EmulatorInstance ld = instance(PlatformId.LDPLAYER, "C:/LDPlayer/ldconsole.exe", "launch", "--index", "0");
        ProductWriters.Plan noRoot = ProductWriters.of(ld, ordered(LOCALE, new ConfigValue.Text("fr-FR"),
                TIME_ZONE, new ConfigValue.Text("Europe/Paris"), POPUPS_OFF, new ConfigValue.Toggle(true)),
                Map.of(ROOT, new ConfigValue.Toggle(false)), file());

        assertTrue(noRoot.refused().containsKey(LOCALE), "a locale needs root");
        assertTrue(noRoot.stopped().isEmpty());
        SettingWrite.Shell zone = (SettingWrite.Shell) noRoot.running().getFirst();
        assertEquals("settings put global auto_time_zone 0", zone.commands().getFirst());
        assertTrue(zone.commands().get(1).contains("cmd alarm set-timezone Europe/Paris; else service call alarm 3 s16 "
                + "Europe/Paris"));
        assertEquals(QuietRun.calmCommands(), ((SettingWrite.Shell) noRoot.running().get(1)).commands());

        ProductWriters.Plan rooted = ProductWriters.of(ld, Map.of(LOCALE, new ConfigValue.Text("fr-FR")),
                Map.of(ROOT, new ConfigValue.Toggle(true)), file());
        SettingWrite.Shell locale = (SettingWrite.Shell) rooted.running().getFirst();
        assertTrue(locale.root());
        assertEquals(List.of("setprop persist.sys.locale fr-FR"), locale.commands());

        ProductWriters.Plan odd = ProductWriters.of(ld, Map.of(TIME_ZONE, new ConfigValue.Text("Paris; reboot")),
                Map.of(), file());
        assertTrue(odd.refused().containsKey(TIME_ZONE), "a zone is never passed to the shell unchecked");
    }

    @Test
    void gameLoopTakesNothing() {
        ProductWriters.Plan plan = ProductWriters.of(instance(PlatformId.GAMELOOP, "C:/GameLoop/AppMarket.exe"),
                Map.of(FPS, new ConfigValue.Count(30)), Map.of(), file());
        assertTrue(plan.isEmpty());
        assertTrue(plan.refused().containsKey(FPS));
    }

    private static void assertPair(List<String> command, String key, String value) {
        int at = command.indexOf(key);
        assertTrue(at > 0 && "-k".equals(command.get(at - 1)), key + " is given with -k");
        assertEquals("-val", command.get(at + 1));
        assertEquals(value, command.get(at + 2), key);
    }

    private static Map<ConfigField, ConfigValue> ordered(Object... pairs) {
        Map<ConfigField, ConfigValue> out = new EnumMap<>(ConfigField.class);
        for (int i = 0; i < pairs.length; i += 2) out.put((ConfigField) pairs[i], (ConfigValue) pairs[i + 1]);
        return out;
    }

    private static Function<EmulatorInstance, Optional<Path>> none() {
        return instance -> Optional.empty();
    }

    private static Function<EmulatorInstance, Optional<Path>> file() {
        return instance -> Optional.of(FILE);
    }

    private static EmulatorInstance instance(PlatformId platform, String... launch) {
        return new EmulatorInstance(platform, platform.displayName(), "127.0.0.1", 5555)
                .withCommands(List.of(launch), List.of());
    }
}
