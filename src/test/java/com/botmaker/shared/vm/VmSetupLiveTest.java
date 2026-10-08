package com.botmaker.shared.vm;

import com.botmaker.shared.vnc.VncController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real game VM, end to end, on this computer: from the Windows ISO named by {@code -Dbotmaker.vm.iso} to a
 * signed-in desktop, with whichever hypervisor {@link Hypervisor#detect()} finds (or {@code -Dbotmaker.vm.hypervisor}).
 * Then it measures the screen's frame rate over VNC and checks a key and a click land. It takes 20–40 minutes
 * the first time; run again, it resumes or reuses the VM ({@code -Dbotmaker.vm.name}, default {@code live}).
 * Results go to stdout and {@code live-results.txt} in the VM's folder.
 */
@EnabledIfSystemProperty(named = "botmaker.vm.iso", matches = ".+")
class VmSetupLiveTest {

    @Test
    void aVmFromTheIsoReachesADesktopThatTakesKeysAndClicks() throws Exception {
        Path iso = Path.of(System.getProperty("botmaker.vm.iso"));
        String name = System.getProperty("botmaker.vm.name", "live");
        Hypervisor hypervisor = System.getProperty("botmaker.vm.hypervisor") != null
                ? Hypervisor.fromId(System.getProperty("botmaker.vm.hypervisor")) : Hypervisor.detect();
        VmSize size = new VmSize(Integer.getInteger("botmaker.vm.cpus", 2),
                Integer.getInteger("botmaker.vm.memory", 4096), VmSize.DEFAULT_DISK_GB);
        List<String> results = new ArrayList<>();
        VmSetup.Listener listener = new VmSetup.Listener() {
            int frames;

            @Override
            public void step(String sentence) {
                say(results, sentence);
            }

            @Override
            public void frame(BufferedImage screen) {
                if (++frames % 60 == 0) say(results, "… screen " + screen.getWidth() + "x" + screen.getHeight());
            }
        };

        Path folder = VmInventory.folder(name);
        VmRecord vm = Files.exists(folder.resolve(VmRecord.FILE)) ? VmRecord.load(folder)
                : VmSetup.prepare(name, iso, hypervisor, size, listener);
        long started = System.nanoTime();
        vm = VmSetup.install(vm, listener, Duration.ofHours(3));
        say(results, "install took " + Duration.ofNanos(System.nanoTime() - started).toMinutes() + " min");
        assertEquals(VmRecord.Stage.READY, vm.stage());

        VmCredentials credentials = VmCredentials.load(vm.folder()).orElseThrow();
        try (VmSetup.Running running = VmSetup.start(vm, credentials)) {
            VncController screen = running.screen();
            vm = running.vm();
            long seen = screen.frames();
            Thread.sleep(10_000);
            say(results, String.format("idle updates: %.1f/s at %s", (screen.frames() - seen) / 10.0, screen.screenSize()));

            BufferedImage before = screen.captureScreen();
            screen.keyDown(0x5B); // the Windows key opens Start
            screen.keyUp(0x5B);
            Thread.sleep(2_000);
            double keyChange = changed(before, screen.captureScreen());
            screen.keyDown(0x1B);
            screen.keyUp(0x1B);
            Thread.sleep(1_500);
            say(results, String.format("Windows key changed %.1f%% of the screen", keyChange * 100));

            BufferedImage desktop = screen.captureScreen();
            screen.click(desktop.getWidth() / 2, desktop.getHeight() / 2, 3); // the desktop's context menu
            Thread.sleep(2_000);
            double clickChange = changed(desktop, screen.captureScreen());
            screen.keyDown(0x1B);
            screen.keyUp(0x1B);
            say(results, String.format("right-click changed %.1f%% of the screen", clickChange * 100));

            Files.write(vm.folder().resolve("live-results.txt"), results);
            assertTrue(keyChange > 0.005, "the Windows key opened nothing");
            assertTrue(clickChange > 0.001, "the right-click opened nothing");
        }
    }

    private static void say(List<String> results, String line) {
        String stamped = LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + line;
        results.add(stamped);
        System.out.println(stamped);
    }

    /** The share of pixels that differ between two screens of the same size. */
    private static double changed(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return 1;
        long differ = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) differ++;
            }
        }
        return differ / ((a.getWidth() / 2.0) * (a.getHeight() / 2.0));
    }
}
