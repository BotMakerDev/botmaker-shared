package com.botmaker.shared.launch;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.linux.LinuxController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lutris games launched for real on this desktop (2026-10-30): each Lutris id named by
 * {@code -Dbotmaker.lutris.live=14,15} is started through {@link Launcher#start}, seen running by
 * {@link Launcher#isRunning}, and its window found by Lutris's title and captured. The frame must be lit, not the
 * black a compositor hands back for a window it doesn't draw. Each frame is kept in {@code target/lutris-live/}.
 * It starts games on the desktop it runs on, so only on purpose.
 */
@EnabledIfSystemProperty(named = "botmaker.lutris.live", matches = ".+")
class LutrisLaunchLiveTest {

    private static final Duration UP = Duration.ofMinutes(3);
    /** A title screen fades in: a frame taken this long after the window appeared shows it. */
    private static final Duration SETTLE = Duration.ofSeconds(20);

    @Test
    void eachNamedGameStartsIsSeenRunningAndShowsALitWindow() throws Exception {
        String[] ids = System.getProperty("botmaker.lutris.live").split(",");
        for (String id : Arrays.stream(ids).map(String::strip).toList()) {
            LutrisLibrary.Game game = LutrisLibrary.find(id);
            assertNotNull(game, "Lutris lists no game " + id);
            LaunchSpec spec = LaunchSpec.parse("lutris:" + id);
            say(game.name() + " (" + game.runner() + "): " + (Launcher.isRunning(spec) ? "already running" : "starting"));
            if (!Launcher.isRunning(spec)) Launcher.start(spec);

            long deadline = System.nanoTime() + UP.toNanos();
            while (!Launcher.isRunning(spec)) {
                if (System.nanoTime() > deadline) fail(game.name() + " was not seen running within " + UP);
                Thread.sleep(1000);
            }
            say(game.name() + ": running");

            try (LinuxController controller = new LinuxController(System.getenv("DISPLAY"), null)) {
                Optional<GenericWindow> window = Optional.empty();
                while (window.isEmpty()) {
                    if (System.nanoTime() > deadline) fail(game.name() + " opened no window proven its own within " + UP);
                    // Proven by the game's processes, not by title: Steam's "Select launch option" dialog is titled
                    // after the game too.
                    window = GameWindows.proven(GameWindows.of(spec, null, controller.getAllWindows()));
                    if (window.isEmpty()) Thread.sleep(1000);
                }
                say(game.name() + ": window " + GameWindows.describe(window.get()));
                Thread.sleep(SETTLE.toMillis());

                BufferedImage frame = controller.captureWindow(window.get());
                assertNotNull(frame, game.name() + "'s window gave no frame");
                Path out = Path.of("target", "lutris-live", id + ".png");
                Files.createDirectories(out.getParent());
                ImageIO.write(frame, "png", out.toFile());
                double lit = litShare(frame);
                say(game.name() + ": " + frame.getWidth() + "x" + frame.getHeight() + ", " + Math.round(lit * 100)
                        + "% lit, " + out.toAbsolutePath());
                assertTrue(lit > 0.05, game.name() + "'s frame is black: " + out.toAbsolutePath());
            }
        }
    }

    /** The share of pixels, on a 64×64 grid, brighter than near-black. */
    private static double litShare(BufferedImage frame) {
        int lit = 0;
        int samples = 0;
        for (int gy = 0; gy < 64; gy++) {
            for (int gx = 0; gx < 64; gx++) {
                int rgb = frame.getRGB(gx * (frame.getWidth() - 1) / 63, gy * (frame.getHeight() - 1) / 63);
                int max = Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
                samples++;
                if (max > 24) lit++;
            }
        }
        return (double) lit / samples;
    }

    private static void say(String line) {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + line);
    }
}
