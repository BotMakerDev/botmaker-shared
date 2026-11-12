package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Apply and Undo on a real instance of this PC: {@code -Dbotmaker.live.apply=<platform id>[:<instance name>]}. It
 * sets a portrait screen (900x1600, or 720x1280 when it has that) and another frame rate, starts the instance to see what Android reports (the
 * orientation each product's writer maps to), then undoes both and checks the settings read as they were. Skipped
 * unless the property is set.
 */
@EnabledIfSystemProperty(named = "botmaker.live.apply", matches = ".+")
class ApplyLiveTest {

    private static final ConfigValue.Screen PORTRAIT = new ConfigValue.Screen(900, 1600, 240);
    /** For an instance that already has {@link #PORTRAIT}. */
    private static final ConfigValue.Screen OTHER_PORTRAIT = new ConfigValue.Screen(720, 1280, 240);

    @TempDir
    Path dir;

    @Test
    void applyThenUndo() throws Exception {
        String[] picked = System.getProperty("botmaker.live.apply").strip().split(":", 2);
        PlatformId platform = PlatformId.fromId(picked[0]);
        EmulatorInstance instance = Platforms.discoverAll().stream()
                .filter(i -> i.platformId() == platform && (picked.length == 1 || i.name().equals(picked[1])))
                .findFirst().orElseThrow(() -> new AssertionError("no " + String.join(":", picked) + " instance"));
        ApplyHost host = ApplyHost.live();
        Applies log = new Applies(dir.resolve("applies.jsonl"));
        boolean wasRunning = host.running(instance);
        System.out.println("== " + instance.brand() + " / " + instance.name() + " at " + instance.endpoint()
                + (wasRunning ? " (running)" : " (stopped)"));

        InstanceFacts before = host.read(instance, null);
        System.out.println("settings before: " + before.settings());
        ConfigValue fpsBefore = before.settings().get(ConfigField.FPS);
        ConfigValue.Count fps = new ConfigValue.Count(fpsBefore instanceof ConfigValue.Count(int n) && n == 30 ? 40 : 30);
        ConfigValue screenBefore = before.settings().get(ConfigField.SCREEN);
        ConfigValue.Screen screen = PORTRAIT.equals(screenBefore) ? OTHER_PORTRAIT : PORTRAIT;
        List<Apply.Change> changes = screenBefore == null
                ? List.of(new Apply.Change(ConfigField.FPS, fpsBefore, fps))
                : List.of(new Apply.Change(ConfigField.SCREEN, screenBefore, screen),
                new Apply.Change(ConfigField.FPS, fpsBefore, fps));
        System.out.println("applying: " + changes);

        Apply.Outcome applied = new Apply(host, instance, before, log).run(changes, printing());
        System.out.println("refused: " + applied.refused());
        assertNull(applied.failed(), applied.failed());
        InstanceFacts after = host.read(instance, before);
        System.out.println("settings after: " + after.settings());
        for (Apply.Change change : applied.written()) {
            assertEquals(change.after(), after.settings().get(change.field()), change.field() + " reads as applied");
        }

        if (applied.written().stream().anyMatch(c -> c.field() == ConfigField.SCREEN)) {
            if (!host.running(instance)) assertNull(host.start(instance, System.out::println));
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                String size = device.shell("wm size");
                String density = device.shell("wm density");
                System.out.println("Android says: " + size.strip().replace('\n', ' ') + " / "
                        + density.strip().replace('\n', ' '));
                assertTrue(size.contains("Physical size: " + screen.width() + "x" + screen.height()), instance.brand()
                        + "'s writer maps the screen wrong: Android says " + size.strip());
            }
        }

        Applies.Entry entry = log.last(InstanceKey.of(instance)).orElseThrow();
        Apply.Outcome undone = Apply.undo(host, instance, after, log, entry, printing());
        assertNull(undone.failed(), undone.failed());
        InstanceFacts back = host.read(instance, after);
        System.out.println("settings after undo: " + back.settings());
        for (Apply.Change change : applied.written()) {
            if (change.before() != null) {
                assertEquals(change.before(), back.settings().get(change.field()), change.field() + " is back");
            }
        }
        if (!wasRunning && host.running(instance)) assertNull(host.stop(instance));
        if (wasRunning && !host.running(instance)) assertNull(host.start(instance, System.out::println));
        assertTrue(log.last(InstanceKey.of(instance)).isEmpty());
        System.out.println("== done, " + Map.of("applied", applied.written().size(), "undone",
                undone.written().size()));
    }

    private static Operation.Context printing() {
        return new Operation.Context() {
            @Override
            public void step(String words) {
                System.out.println("  · " + words);
            }

            @Override
            public void progress(double fraction) {
            }

            @Override
            public void needsYou(String why) {
                System.out.println("  ! " + why);
            }

            @Override
            public void resumed() {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }
}
