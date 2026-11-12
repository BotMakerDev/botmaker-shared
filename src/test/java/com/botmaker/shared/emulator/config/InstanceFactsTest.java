package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an instance has now, over outputs captured live on 2026-11-12 with nothing pressed: Android on GameLoop (13),
 * MuMu (15) and MEmu (9); the settings of LDPlayer 14, BlueStacks 5, MuMu 12 and MEmu 9.
 */
class InstanceFactsTest {

    private static final Instant AT = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void gameLoopsAndroid() throws IOException {
        AndroidState state = AndroidState.read(captured("gameloop"), AT);

        assertEquals(new ConfigValue.Screen(480, 320, 260), state.fields().get(ConfigField.SCREEN));
        assertEquals(new ConfigValue.Text("en-US"), state.fields().get(ConfigField.LOCALE));
        assertEquals(new ConfigValue.Text("Asia/Shanghai"), state.fields().get(ConfigField.TIME_ZONE));
        assertNull(state.fields().get(ConfigField.ROOT), "no su: root is the product's to say");
        assertEquals("13", state.android());
        assertEquals(Optional.of(Set.of()), state.googleAccounts());
        assertEquals(Optional.of(Set.of("com.zjcs.android.us")), state.packages());
        assertFalse(state.doNotDisturbNow());
    }

    @Test
    void anOverrideIsWhatAppsSeeAndAnEmptyLocaleFallsBackToTheImages() throws IOException {
        AndroidState mumu = AndroidState.read(captured("mumu"), AT);

        assertEquals(new ConfigValue.Screen(1080, 1920, 288), mumu.fields().get(ConfigField.SCREEN));
        assertEquals(new ConfigValue.Text("en-US"), mumu.fields().get(ConfigField.LOCALE));
        assertEquals("15", mumu.android());
        assertTrue(mumu.doNotDisturbNow(), "zen_mode 2, left on by an earlier run");
        assertEquals(Optional.of(Set.of("com.zjcs.android.us", "com.darkempire78.opencalculator")), mumu.packages());

        AndroidState memu = AndroidState.read(captured("memu"), AT);
        assertEquals(new ConfigValue.Screen(1920, 1080, 360), memu.fields().get(ConfigField.SCREEN));
        assertEquals(new ConfigValue.Text("zh-CN"), memu.fields().get(ConfigField.LOCALE));
        assertEquals("9", memu.android());
    }

    @Test
    void onlyTheSignedInBlocksGoogleAccountsAreListed() throws IOException {
        Set<String> found = AndroidState.googleAccounts(resource("signed-in-accounts.txt"));

        assertEquals(Set.of("first.player@example.com"), found);
    }

    @Test
    void aCommandThatAnsweredNothingIsUnreadNotEmpty() {
        AndroidState state = AndroidState.read(command -> command.startsWith("wm") ? "Physical size: 900x1600\n"
                + "Physical density: 240" : null, AT);

        assertEquals(Optional.empty(), state.googleAccounts());
        assertEquals(Optional.empty(), state.packages());
        assertNull(AndroidState.googleAccounts("Error: can't find service: account"));
        assertNull(AndroidState.packages("cmd: Failure calling service package"));
        assertNull(AndroidState.packages("\n"), "a booting package service answers blank");
    }

    @Test
    void ldPlayersFile() throws IOException {
        Map<ConfigField, ConfigValue> read = ProductSettings.LDPLAYER.parse(instance(PlatformId.LDPLAYER,
                "dnplayer.exe", "--index", "0"), resource("ldplayer/leidian0.config"));

        assertEquals(new ConfigValue.Screen(1600, 900, 240), read.get(ConfigField.SCREEN));
        assertEquals(new ConfigValue.Toggle(true), read.get(ConfigField.ADB));
        assertFalse(read.containsKey(ConfigField.CPU_CORES), "a new instance's file has no cpuCount");
        assertFalse(read.containsKey(ConfigField.ROOT));

        Map<ConfigField, ConfigValue> changed = ProductSettings.LDPLAYER.parse(instance(PlatformId.LDPLAYER,
                "dnplayer.exe", "--index", "0"), "{\"advancedSettings.cpuCount\": 4, \"advancedSettings.memorySize\": "
                + "4096, \"basicSettings.fps\": 60, \"basicSettings.rootMode\": true}");
        assertEquals(new ConfigValue.Count(4), changed.get(ConfigField.CPU_CORES));
        assertEquals(new ConfigValue.Megabytes(4096), changed.get(ConfigField.MEMORY));
        assertEquals(new ConfigValue.Count(60), changed.get(ConfigField.FPS));
        assertEquals(new ConfigValue.Toggle(true), changed.get(ConfigField.ROOT));
        assertEquals(new ConfigValue.Toggle(false), changed.get(ConfigField.ADB), "no adbDebug: ADB off");
    }

    @Test
    void blueStacksReadsItsOwnInstancesLinesOnly() throws IOException {
        Map<ConfigField, ConfigValue> read = ProductSettings.BLUESTACKS.parse(instance(PlatformId.BLUESTACKS,
                "HD-Player.exe", "--instance", "Pie64"), resource("bluestacks/bluestacks.conf"));

        assertEquals(new ConfigValue.Screen(1600, 900, 240), read.get(ConfigField.SCREEN));
        assertEquals(new ConfigValue.Count(4), read.get(ConfigField.CPU_CORES));
        assertEquals(new ConfigValue.Megabytes(4096), read.get(ConfigField.MEMORY));
        assertEquals(new ConfigValue.Count(60), read.get(ConfigField.FPS));
        assertEquals(new ConfigValue.Toggle(false), read.get(ConfigField.ROOT));
        assertEquals(new ConfigValue.Toggle(true), read.get(ConfigField.ADB));

        Map<ConfigField, ConfigValue> noToken = ProductSettings.BLUESTACKS.parse(instance(PlatformId.BLUESTACKS,
                "HD-Player.exe"), resource("bluestacks/bluestacks.conf"));
        assertFalse(noToken.containsKey(ConfigField.CPU_CORES), "no --instance: its lines stay unread");
    }

    @Test
    void muMusConsoleAndMEmusFile() throws IOException {
        Map<ConfigField, ConfigValue> mumu = ProductSettings.muMu(resource("mumu/setting_all.json"));
        assertEquals(new ConfigValue.Screen(1600, 900, 240), mumu.get(ConfigField.SCREEN));
        assertEquals(new ConfigValue.Count(4), mumu.get(ConfigField.CPU_CORES));
        assertEquals(new ConfigValue.Megabytes(4096), mumu.get(ConfigField.MEMORY));
        assertEquals(new ConfigValue.Count(60), mumu.get(ConfigField.FPS));
        assertEquals(new ConfigValue.Toggle(false), mumu.get(ConfigField.ROOT));
        assertEquals(Map.of(), ProductSettings.muMu("MuMuManager: no such player"));

        Map<ConfigField, ConfigValue> memu = ProductSettings.MEMU.parse(instance(PlatformId.MEMU,
                "MEmu.exe", "-n", "MEmu"), resource("memu/MEmu.memu"));
        assertEquals(new ConfigValue.Screen(1600, 900, 300), memu.get(ConfigField.SCREEN));
        assertEquals(new ConfigValue.Count(6), memu.get(ConfigField.CPU_CORES));
        assertEquals(new ConfigValue.Megabytes(6144), memu.get(ConfigField.MEMORY));
        assertEquals(new ConfigValue.Toggle(false), memu.get(ConfigField.ROOT));
        assertFalse(memu.containsKey(ConfigField.FPS), "MEmu keeps no fps until it is set");
    }

    @Test
    void theScreenIsLiveAndroidsAndTheRestTheSettingsFirst() throws IOException {
        InstanceKey key = new InstanceKey(PlatformId.MUMU, "MuMu-0");
        AndroidState android = AndroidState.read(captured("mumu"), AT);
        Map<ConfigField, ConfigValue> settings = ProductSettings.muMu(resource("mumu/setting_all.json"));
        Instant later = AT.plusSeconds(60);

        InstanceFacts live = new InstanceFacts(key, settings, later, Optional.of(android), true);
        assertEquals(InstanceFacts.Origin.ANDROID, live.field(ConfigField.SCREEN).orElseThrow().origin());
        assertEquals(InstanceFacts.Origin.SETTINGS, live.field(ConfigField.CPU_CORES).orElseThrow().origin());
        assertEquals(new InstanceFacts.Read(new ConfigValue.Text("Europe/Budapest"), InstanceFacts.Origin.ANDROID, AT),
                live.field(ConfigField.TIME_ZONE).orElseThrow());

        InstanceFacts stopped = new InstanceFacts(key, settings, later, Optional.of(android), false);
        assertEquals(new InstanceFacts.Read(new ConfigValue.Screen(1600, 900, 240), InstanceFacts.Origin.SETTINGS,
                later), stopped.field(ConfigField.SCREEN).orElseThrow());
        assertEquals(AT, stopped.field(ConfigField.LOCALE).orElseThrow().at(), "kept with its time");
    }

    @Test
    void aStoppedInstanceKeepsTheLastAndroidRead() throws IOException {
        EmulatorInstance memu = instance(PlatformId.MEMU, "C:/nowhere/MEmu.exe", "-n", "MEmu");
        AndroidState android = AndroidState.read(captured("memu"), AT);
        InstanceFacts last = new InstanceFacts(InstanceKey.of(memu), Map.of(), AT, Optional.of(android), true);

        InstanceFacts now = InstanceFacts.read(memu, null, last, Clock.fixed(AT.plusSeconds(300), ZoneOffset.UTC));

        assertEquals(Optional.of(android), now.android());
        assertFalse(now.androidLive());
        assertEquals(new ConfigValue.Toggle(true), now.settings().get(ConfigField.ADB), "MEmu's ADB is always on");
        assertEquals(AT.plusSeconds(300), now.settingsAt());
    }

    private static EmulatorInstance instance(PlatformId platform, String... launch) {
        return new EmulatorInstance(platform, platform.displayName(), "127.0.0.1", 5555)
                .withCommands(List.of(launch), List.of());
    }

    /** A shell answering as the captured instance did. */
    private static AndroidState.Shell captured(String product) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(resource(product + "/props.properties")));
        Map<String, String> answers = Map.of(
                "wm size", resource(product + "/wm_size.txt"),
                "wm density", resource(product + "/wm_density.txt"),
                "which su", resource(product + "/which_su.txt"),
                "settings get global zen_mode", resource(product + "/zen_mode.txt"),
                "dumpsys account", resource(product + "/accounts.txt"),
                "pm list packages -3", resource(product + "/packages.txt"));
        return command -> command.startsWith("getprop ")
                ? props.getProperty(command.substring("getprop ".length()), "")
                : answers.get(command);
    }

    static String resource(String name) throws IOException {
        try (InputStream in = InstanceFactsTest.class.getResourceAsStream("/emulator/facts/" + name)) {
            if (in == null) throw new IOException("no resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
