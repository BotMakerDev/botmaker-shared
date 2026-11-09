package com.botmaker.shared.launch;

import com.botmaker.shared.platform.Os;
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
    void heroicPassesTheProtocolUrlAsAnArgument() {
        // Heroic has no `launch` subcommand: the request IS the heroic:// URL in argv. The old
        // `--no-gui launch <id>` spelling booted a hidden, idle Heroic that launched nothing.
        assertEquals(List.of(
                List.of("heroic", "--no-gui", "--no-sandbox", "heroic://launch/AbC123"),
                List.of("flatpak", "run", "com.heroicgameslauncher.hgl",
                        "--no-gui", "--no-sandbox", "heroic://launch/AbC123")),
            LaunchCommands.heroic("AbC123"));
    }

    @Test
    void anAppStartsThroughGtkLaunchOrOnAMacThroughOpen() {
        assertEquals(List.of(List.of("gtk-launch", "org.gnome.Chess")),
            LaunchCommands.desktop("org.gnome.Chess", Os.LINUX));
        assertEquals(List.of(List.of("open", "-a", "/Applications/Hades.app")),
            LaunchCommands.desktop("/Applications/Hades.app", Os.MAC));
    }

    @Test
    void aFlatpakAppInAPrivateDisplayIsOfferedNoWaylandSocket() {
        assertEquals(List.of(List.of("env", "-u", "WAYLAND_DISPLAY", "gtk-launch", "com.github.tenderowl.frog")),
            LaunchCommands.withoutWayland(LaunchCommands.desktop("com.github.tenderowl.frog", Os.LINUX)));
        assertEquals("gtk-launch", LaunchCommands.executable(List.of("env", "-u", "WAYLAND_DISPLAY", "gtk-launch", "x")),
            "the install check looks past the env prefix");
        assertEquals("steam", LaunchCommands.executable(List.of("steam", "-applaunch", "570")));
    }

    @Test
    void steamLaddersNativeThenFlatpak() {
        assertEquals(List.of(
                List.of("steam", "-applaunch", "570"),
                List.of("flatpak", "run", "com.valvesoftware.Steam", "-applaunch", "570")),
            LaunchCommands.steam("570"));
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
        assertEquals(LaunchCommands.steam("570"),
            LaunchCommands.childLadder(LaunchSpec.parse("steam:570")));
        // Heroic builds its ladder where it still runs; on Linux it was retired for Lutris (2026-10-29).
        assertEquals(com.botmaker.shared.platform.Os.current() == com.botmaker.shared.platform.Os.LINUX
                ? List.of() : LaunchCommands.heroic("AbC123"),
            LaunchCommands.childLadder(LaunchSpec.parse("heroic:AbC123")));
    }

    @Test
    void kindsWithNoChildFormLadderToEmpty() {
        assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("epic:Fortnite")).isEmpty());
        assertTrue(LaunchCommands.childLadder(null).isEmpty());
    }

    /**
     * Every Android product but Waydroid is reached over ADB after something else started it, so there is
     * nothing to hand a private display to. Asserted through the public entry point because the name check is
     * what keeps this path from probing the host on every launch.
     */
    @Test
    void anEmulatorAppOnAnyOtherProductStillHasNoChildForm() {
        assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("emu-app:com.foo@Main")).isEmpty());
        assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("emu-app:com.foo@MuMu Player")).isEmpty());
    }

    /**
     * An emulator app has no child command for a private display, Waydroid included (2026-11-09): it is read and
     * pressed over ADB, its session under a headless gamescope.
     */
    @Test
    void waydroidHasNoPrivateDisplayLadder() {
        assertTrue(LaunchCommands.childLadder(LaunchSpec.parse("emu-app:com.foo@Waydroid")).isEmpty());
    }

    @Test
    void blankTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LaunchCommands.heroic("  "));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommands.steam(null));
    }
}
