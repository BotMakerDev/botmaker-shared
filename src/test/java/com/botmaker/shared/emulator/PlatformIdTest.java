package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The product enum that replaced the free-form {@code String platformId}. */
public class PlatformIdTest {

    @Test
    void everyProductKeepsTheWireIdItHadAsAString() {
        // These ids may already be stored in a user's config, so they are not free to change.
        assertEquals("bluestacks", PlatformId.BLUESTACKS.id());
        assertEquals("ldplayer", PlatformId.LDPLAYER.id());
        assertEquals("memu", PlatformId.MEMU.id());
        assertEquals("mumu", PlatformId.MUMU.id());
        assertEquals("gameloop", PlatformId.GAMELOOP.id());
    }

    @Test
    void idsAreUniqueSoFromIdIsUnambiguous() {
        Set<String> ids = new HashSet<>();
        for (PlatformId p : PlatformId.values()) {
            assertTrue(ids.add(p.id()), "duplicate id: " + p.id());
        }
    }

    @Test
    void fromIdRoundTripsAndIsTotal() {
        for (PlatformId p : PlatformId.values()) {
            assertEquals(p, PlatformId.fromId(p.id()));
        }
        // Total: an id from a newer or hand-edited config must load as UNKNOWN, never throw.
        assertEquals(PlatformId.UNKNOWN, PlatformId.fromId("nox"));
        assertEquals(PlatformId.UNKNOWN, PlatformId.fromId(null));
        assertEquals(PlatformId.BLUESTACKS, PlatformId.fromId("BlueStacks"));
    }

    @Test
    void everyPlatformReportsItsOwnIdAndTakesItsNameFromIt() {
        for (EmulatorPlatform platform : Platforms.ALL) {
            assertEquals(platform.id().displayName(), platform.displayName());
        }
    }

    @Test
    void instanceIdentityIsPerInstanceNotPerName() {
        // Two products' instances sharing a default name must not collide — identity keys on product + endpoint.
        EmulatorInstance mumu = new EmulatorInstance(PlatformId.MUMU, "Android", "127.0.0.1", 16384);
        EmulatorInstance blue = new EmulatorInstance(PlatformId.BLUESTACKS, "Android", "127.0.0.1", 5565);
        assertEquals("mumu@127.0.0.1:16384", mumu.identity());
        assertTrue(!mumu.identity().equals(blue.identity()));
        assertEquals("MuMu Player", mumu.brand());
    }

    @Test
    void aMissingPlatformDefaultsToUnknownRatherThanNull() {
        assertEquals(PlatformId.UNKNOWN, new EmulatorInstance(null, "x", "127.0.0.1", 5555).platformId());
    }
}
