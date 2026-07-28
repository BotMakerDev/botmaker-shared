package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The child-launchable argv ladders both launch paths share. These are the exact command lines
 * {@link GameLauncher}'s CLI fallbacks and {@code NestedSession}'s nested launch run, so they're pinned here
 * once rather than asserted twice.
 */
class LaunchCommandsTest {

    @Test
    void heroicLaddersNativeThenFlatpak() {
        assertEquals(List.of(
                List.of("heroic", "--no-gui", "launch", "AbC123"),
                List.of("flatpak", "run", "com.heroicgameslauncher.hgl", "--no-gui", "launch", "AbC123")),
            LaunchCommands.heroic("AbC123"));
    }

    @Test
    void steamHasTheApplaunchCliForm() {
        assertEquals(List.of(List.of("steam", "-applaunch", "570")), LaunchCommands.steam("570"));
    }

    @Test
    void faugusLaddersNativeThenFlatpak() {
        assertEquals(List.of(
                List.of("faugus-launcher", "--game", "mygame"),
                List.of("flatpak", "run", "io.github.Faugus.faugus-launcher", "--game", "mygame")),
            LaunchCommands.faugus("mygame"));
    }

    @Test
    void childLadderDispatchesByKind() {
        assertEquals(List.of(List.of("/opt/game/run.sh")),
            LaunchCommands.childLadder(LaunchSpec.parse("exe:/opt/game/run.sh")));
        assertEquals(List.of(List.of("xterm", "-e", "sleep", "300")),
            LaunchCommands.childLadder(LaunchSpec.parse("cli:xterm -e sleep 300")));
        assertEquals(LaunchCommands.heroic("AbC123"),
            LaunchCommands.childLadder(LaunchSpec.parse("heroic:AbC123")));
        assertEquals(LaunchCommands.steam("570"),
            LaunchCommands.childLadder(LaunchSpec.parse("steam:570")));
    }

    @Test
    void kindsWithNoChildFormLadderToEmpty() {
        assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("epic:Fortnite")).isEmpty());
        assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("emu-app:com.foo@Main")).isEmpty());
        assertTrue(LaunchCommands.childLadder(null).isEmpty());
    }

    @Test
    void blankTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LaunchCommands.heroic("  "));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommands.steam(null));
    }
}
