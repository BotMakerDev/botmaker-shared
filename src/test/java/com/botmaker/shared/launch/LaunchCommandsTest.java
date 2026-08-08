package com.botmaker.shared.launch;

import com.botmaker.shared.emulator.WaydroidResolution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals(LaunchCommands.heroic("AbC123"),
            LaunchCommands.childLadder(LaunchSpec.parse("heroic:AbC123")));
        assertEquals(LaunchCommands.steam("570"),
            LaunchCommands.childLadder(LaunchSpec.parse("steam:570")));
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
     * Waydroid's UI is a Wayland client we start ourselves, so it does have one: its own gamescope, in the
     * nested form. {@code --backend sdl} is what makes that gamescope open a window on the {@code DISPLAY} it
     * inherits, and the sizes are equal so nothing between Android and the capture is a scaler.
     */
    @Test
    void waydroidLaddersToItsOwnGamescope() {
        assertEquals(List.of(List.of("env", "-u", "WAYLAND_DISPLAY",
                        "gamescope", "--backend", "sdl",
                        "-W", "1080", "-H", "1920", "-w", "1080", "-h", "1920",
                        "--expose-wayland", "waydroid", "app", "launch", "com.foo")),
                LaunchCommands.emulatorApp("com.foo", new WaydroidResolution(1080, 1920), true));
    }

    /**
     * The display's size wins over the container's, because that rung <em>is</em> the compositor Android
     * renders into: a gamescope sized from the container's landscape properties on a portrait display is the
     * letterboxing the private display exists to remove.
     */
    @Test
    void anEmulatorAppIsSizedByTheDisplayItRunsOn() {
        assertEquals(new WaydroidResolution(1080, 1920), LaunchCommands.nestedSize(1080, 1920));
    }

    /**
     * Only an unstated size lets the container answer. What it answers is deliberately not asserted: it reads
     * the live container, so pinning a value here would make this test pass or fail by what is installed on
     * the machine running it — which it did, once.
     */
    @Test
    void anUnstatedSizeFallsBackToTheContainer() {
        assertNotNull(LaunchCommands.nestedSize(0, 0));
        assertNotNull(LaunchCommands.nestedSize(-1, 1920));
    }

    /** No gamescope, no nested form at all — the launch is then refused loudly rather than run on {@code :0}. */
    @Test
    void waydroidWithoutGamescopeHasNoNestedForm() {
        assertTrue(LaunchCommands.emulatorApp("com.foo", new WaydroidResolution(1080, 1920), false).isEmpty());
        assertTrue(LaunchCommands.emulatorApp("  ", new WaydroidResolution(1080, 1920), true).isEmpty());
    }

    @Test
    void blankTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LaunchCommands.heroic("  "));
        assertThrows(IllegalArgumentException.class, () -> LaunchCommands.steam(null));
    }
}
