// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.shared.emulator.config;

import com.botmaker.shared.emulator.AdbEndpoint;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.PlatformId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each instance its own ADB address: {@link PortPlan}, the port row, and Apply following the port it wrote. */
class PortPlanTest {

    private static final EmulatorInstance PIE64 = new EmulatorInstance(PlatformId.BLUESTACKS, "Pie64", "127.0.0.1",
            5555);
    private static final EmulatorInstance PIE64_1 = new EmulatorInstance(PlatformId.BLUESTACKS, "Pie64_1",
            "127.0.0.1", 5555);
    private static final EmulatorInstance LD = new EmulatorInstance(PlatformId.LDPLAYER, "LDPlayer", "127.0.0.1",
            5555);

    @Test
    void blueStacksMovesOffLdPlayersNumberingAndLdPlayerKeepsIts() {
        List<PortPlan.Move> moves = PortPlan.plan(List.of(PIE64, PIE64_1, LD), EmulatorConfig.EMPTY);
        assertEquals(List.of(new PortPlan.Move(InstanceKey.of(PIE64), "Pie64", 5555, 5565),
                new PortPlan.Move(InstanceKey.of(PIE64_1), "Pie64_1", 5555, 5567)), moves);
        assertEquals("Pie64: 5555 → 5565", moves.getFirst().said());

        EmulatorConfig applied = PortPlan.apply(EmulatorConfig.EMPTY, moves);
        assertEquals(new ConfigValue.Count(5565), applied.instance(InstanceKey.of(PIE64)).fields()
                .get(ConfigField.ADB_PORT));
        assertTrue(PortPlan.plan(List.of(PIE64, PIE64_1, LD), applied).isEmpty(), "planned once, kept");
    }

    @Test
    void theFirstInstanceOnAFreePortKeepsIt() {
        List<PortPlan.Move> moves = PortPlan.plan(List.of(PIE64, PIE64_1), EmulatorConfig.EMPTY);
        assertEquals(List.of(new PortPlan.Move(InstanceKey.of(PIE64_1), "Pie64_1", 5555, 5565)), moves);
    }

    @Test
    void aRowIsKeptAndARowOfAnInstanceNotFoundIsTaken() {
        InstanceKey gone = new InstanceKey(PlatformId.BLUESTACKS, "Gone");
        EmulatorConfig config = EmulatorConfig.EMPTY
                .withInstance(InstanceKey.of(PIE64), Layer.EMPTY.with(ConfigField.ADB_PORT,
                        new ConfigValue.Count(5571)))
                .withInstance(gone, Layer.EMPTY.with(ConfigField.ADB_PORT, new ConfigValue.Count(5565)));
        assertEquals(List.of(new PortPlan.Move(InstanceKey.of(PIE64_1), "Pie64_1", 5555, 5567)),
                PortPlan.plan(List.of(PIE64, PIE64_1, LD), config));
    }

    @Test
    void aFreePortLeavesTheOnesBesideATakenOne() {
        assertEquals(5565, PortPlan.free(Set.of()));
        assertEquals(5567, PortPlan.free(Set.of(5565)));
        assertEquals(5569, PortPlan.free(Set.of(5566)), "5565 and 5567 sit beside it");
        assertEquals(Set.of(5555, 5557, 5559), PortPlan.reserved(List.of(LD)));
        assertTrue(PortPlan.reserved(List.of(PIE64)).isEmpty());
        assertTrue(PortPlan.usable(5565));
        assertFalse(PortPlan.usable(5566));
        assertFalse(PortPlan.usable(1023));
    }

    @Test
    void applyFollowsThePortItWrote() {
        EmulatorInstance moved = Apply.at(PIE64, List.of(new Apply.Change(ConfigField.ADB_PORT,
                new ConfigValue.Count(5555), new ConfigValue.Count(5565))));
        assertEquals(new AdbEndpoint.Tcp("127.0.0.1", 5565), moved.adb());
        assertEquals(PIE64, Apply.at(PIE64, List.of()));
    }

    @Test
    void aPortIsAnInstancesOwnNeverAnEmulatorsNorCopied() {
        Layer port = Layer.EMPTY.with(ConfigField.ADB_PORT, new ConfigValue.Count(5565))
                .with(ConfigField.FPS, new ConfigValue.Count(30));
        assertEquals(Set.of(ConfigField.FPS), EmulatorConfig.EMPTY.withEmulator(PlatformId.BLUESTACKS, port)
                .emulator(PlatformId.BLUESTACKS).fields().keySet(), "dropped, as an import would carry it");
        EmulatorConfig built = new EmulatorConfig(Map.of(PlatformId.BLUESTACKS, port), Map.of());
        assertFalse(built.emulator(PlatformId.BLUESTACKS).fields().containsKey(ConfigField.ADB_PORT),
                "a row read off an emulator is dropped");
        assertEquals(Set.of(ConfigField.FPS), EmulatorConfig.copyable(port).fields().keySet());
    }
}
