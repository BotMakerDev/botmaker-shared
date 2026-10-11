package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tart's pin, its command lines and what is read from its output, and a macOS guest's scripts; no Mac needed. */
class TartTest {

    @Test
    void theReleaseIsPinnedOverTls() {
        assertTrue(Tart.RELEASE.wellFormed());
        assertTrue(Tart.RELEASE.secure());
        assertTrue(Tart.RELEASE.url().contains("/" + Tart.VERSION + "/"));
    }

    @Test
    void tartRunsOnBotMakersOwnVmFolder() {
        assertEquals(List.of("/usr/bin/env", "TART_HOME=/vm/.tart", "/t/tart", "run", "Game VM", "--no-graphics"),
                Tart.command(Path.of("/t/tart"), Path.of("/vm/.tart"), "run", "Game VM", "--no-graphics"));
        // A timeout names Tart, not the env that sets its folder.
        assertEquals("/t/tart", Commands.program(Tart.command(Path.of("/t/tart"), Path.of("/vm/.tart"), "stop")));
        assertEquals("qemu-img", Commands.program(List.of("qemu-img", "create")));
    }

    @Test
    void theVncAddressIsTheLastOneTartPrinted() {
        String log = "--- start\nVNC server is running at vnc://:old@127.0.0.1:5901\n--- start\n"
                + "booting\nVNC server is running at vnc://:s3cret@127.0.0.1:61234\n";
        Tart.Vnc vnc = Tart.vnc(log).orElseThrow();
        assertEquals(new Tart.Vnc("127.0.0.1", 61234, "s3cret"), vnc);
        assertFalse(vnc.toString().contains("s3cret"));
        assertEquals(Optional.empty(), Tart.vnc("booting\n"));
        assertEquals(Optional.empty(), Tart.vnc("VNC server is running at nonsense\n"));
    }

    @Test
    void aMacGetsTheGuestOfItsOwnRelease() {
        assertEquals("ghcr.io/cirruslabs/macos-sequoia-base:latest", Tart.image(GuestOs.MACOS, "15.3.1"));
        assertEquals("ghcr.io/cirruslabs/macos-tahoe-base:latest", Tart.image(GuestOs.MACOS, "26.0"));
        assertEquals("ghcr.io/cirruslabs/macos-sonoma-base:latest", Tart.image(GuestOs.MACOS, "14.7"));
        assertThrows(IllegalArgumentException.class, () -> Tart.image(GuestOs.LINUX, "15.0"));
        assertEquals(0, Tart.macosMajor("garbage"));
    }

    @Test
    void runningIsReadOffTheList() {
        String json = "[{\"Name\":\"Other\",\"Running\":true},{\"Name\":\"Game VM\",\"State\":\"running\"},"
                + "{\"Name\":\"Off\",\"Running\":false,\"State\":\"stopped\"}]";
        assertTrue(Tart.runningIn(json, "Game VM"));
        assertFalse(Tart.runningIn(json, "Off"));
        assertFalse(Tart.runningIn(json, "Missing"));
        assertFalse(Tart.runningIn("not json", "Game VM"));
    }

    @Test
    void aDesktopLaunchRunsAsTheSignedInUser() {
        String script = TartSetup.asDesktopUser("open -a '/Applications/Hades.app'");
        assertTrue(script.contains("launchctl asuser \"$(id -u admin)\" sudo -u admin /bin/sh -c "));
        assertTrue(script.contains("else exec /bin/sh -c 'open -a '\\''/Applications/Hades.app'\\'''"));
    }

    @Test
    void aMacGamesProcessesAreFoundWithoutThePathInTheShellsOwnCommandLine() {
        String app = "/Applications/Hades.app";
        String found = GuestGame.macScript(LaunchSpec.parse("desktop:" + app), false).orElseThrow();
        assertFalse(found.contains(app), "pgrep -f would find the script's own shell");
        assertTrue(found.contains(Base64.getEncoder().encodeToString(app.getBytes(StandardCharsets.UTF_8))));
        assertTrue(found.contains("\"$T/Contents/MacOS/\""));
        assertFalse(found.contains("pkill"));
        String stop = GuestGame.macScript(LaunchSpec.parse("exe:/Users/admin/game"), true).orElseThrow();
        assertTrue(stop.contains("\"$T\" | sed"));
        assertTrue(stop.contains("pkill -9 -f"));
        assertTrue(stop.contains("printf '" + GuestGame.SURVIVED + "\\t%s\\t%s\\n'"));
        assertEquals(Optional.empty(), GuestGame.macScript(LaunchSpec.parse("steam:570"), false));
        assertEquals(Optional.empty(), GuestGame.macScript(LaunchSpec.parse("desktop:org.gnome.Chess"), false));
    }

    /**
     * The script itself, run here: a bundle whose path has a regular expression's characters, its program a copy of
     * {@code sleep}. GNU's {@code base64} spells macOS's {@code -D} as {@code -d}.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void aMacGamesScriptFindsAndStopsItsBundlesProcess(@TempDir Path root) throws Exception {
        Path app = root.resolve("Game (Beta)+[1].app");
        Path program = Files.createDirectories(app.resolve("Contents/MacOS")).resolve("Game");
        Files.copy(Path.of("/bin/sleep"), program);
        program.toFile().setExecutable(true);
        Process game = new ProcessBuilder(program.toString(), "60").start();
        try {
            LaunchSpec spec = new LaunchSpec(LaunchKind.DESKTOP, app.toString());
            GuestGame.Found running = GuestGame.parse(run(GuestGame.macScript(spec, false).orElseThrow()));
            assertEquals(RunState.RUNNING, running.state());
            assertEquals(List.of("Game (" + game.pid() + ")"), running.processes());
            GuestGame.Found stopped = GuestGame.parse(run(GuestGame.macScript(spec, true).orElseThrow()));
            assertEquals(List.of(), stopped.survived());
            assertTrue(game.waitFor(5, TimeUnit.SECONDS));
            LaunchSpec gone = new LaunchSpec(LaunchKind.DESKTOP, root.resolve("Gone.app").toString());
            assertEquals(GuestGame.Found.UNKNOWN, GuestGame.parse(run(GuestGame.macScript(gone, false).orElseThrow())));
        } finally {
            game.destroyForcibly();
        }
    }

    private static String run(String script) throws Exception {
        Process sh = new ProcessBuilder("/bin/sh", "-c", script.replace("base64 -D", "base64 -d"))
                .redirectErrorStream(true).start();
        String out = new String(sh.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(sh.waitFor(20, TimeUnit.SECONDS));
        return out;
    }

    @Test
    void aTartRecordKeepsItsImage(@TempDir Path folder) throws Exception {
        VmRecord vm = new VmRecord(folder, "Game VM", Hypervisor.TART, VmRecord.Stage.READY, new VmSize(4, 8192, 80),
                Path.of(""), "en-US", 0, 0, 0, 0, GuestOs.MACOS, "ghcr.io/cirruslabs/macos-sequoia-base:latest");
        vm.save();
        assertEquals(vm, VmRecord.load(folder));
        assertTrue(Files.readString(folder.resolve(VmRecord.FILE)).contains("guestOs=macos"));
    }
}
