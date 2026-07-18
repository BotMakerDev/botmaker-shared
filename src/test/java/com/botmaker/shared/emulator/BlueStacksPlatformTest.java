package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code bluestacks.conf} parser — no BlueStacks install required.
 */
class BlueStacksPlatformTest {

    @Test
    void parsesInstancesWithPortsAndDisplayNames() {
        String conf = String.join("\n",
                "bst.feature.rooting=\"1\"",
                "bst.instance.Rvc64.display_name=\"My Main\"",
                "bst.instance.Rvc64.status.adb_port=\"5555\"",
                "bst.instance.Rvc64_1.display_name=\"Second\"",
                "bst.instance.Rvc64_1.status.adb_port=\"5565\"",
                "bst.installed_images=\"Rvc64\"");

        List<EmulatorInstance> instances = BlueStacksPlatform.parseConf(conf);

        assertEquals(2, instances.size());
        assertEquals("bluestacks", instances.get(0).platformId());
        assertEquals("My Main", instances.get(0).name());
        assertEquals("127.0.0.1", instances.get(0).host());
        assertEquals(5555, instances.get(0).adbPort());
        assertEquals("Second", instances.get(1).name());
        assertEquals(5565, instances.get(1).adbPort());
    }

    @Test
    void fallsBackToTokenWhenNoDisplayName() {
        String conf = "bst.instance.Pie64.status.adb_port=\"5575\"";

        List<EmulatorInstance> instances = BlueStacksPlatform.parseConf(conf);

        assertEquals(1, instances.size());
        assertEquals("Pie64", instances.get(0).name());
        assertEquals(5575, instances.get(0).adbPort());
    }

    @Test
    void emptyConfYieldsNoInstances() {
        assertTrue(BlueStacksPlatform.parseConf("").isEmpty());
        assertTrue(BlueStacksPlatform.parseConf("bst.something.else=\"1\"").isEmpty());
    }
}
