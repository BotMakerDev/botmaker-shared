package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real Linux game VM, end to end, on QEMU: Ubuntu downloaded, installed with nobody at the keyboard, and its
 * first start's Steam, Legendary and display tools there, as the guest agent finds them. Named by
 * {@code -Dbotmaker.vm.linux}; run again, it resumes or reuses that VM. It takes 20–40 minutes the first time,
 * plus the 4 GB download.
 */
@EnabledIfSystemProperty(named = "botmaker.vm.linux", matches = ".+")
class LinuxVmLiveTest {

    @Test
    void ubuntuInstallsUnattendedWithSteamLegendaryAndTheDisplayTools() throws Exception {
        String name = System.getProperty("botmaker.vm.linux");
        VmSize size = new VmSize(Integer.getInteger("botmaker.vm.cpus", 2),
                Integer.getInteger("botmaker.vm.memory", 4096), VmSize.DEFAULT_DISK_GB);
        VmSetup.Listener listener = new VmSetup.Listener() {
            int frames;

            @Override
            public void step(String sentence) {
                say(sentence);
            }

            @Override
            public void frame(BufferedImage screen) {
                if (++frames % 60 == 0) say("… screen " + screen.getWidth() + "x" + screen.getHeight());
            }
        };

        Path folder = VmInventory.folder(name);
        VmRecord vm = Files.exists(folder.resolve(VmRecord.FILE)) ? VmRecord.load(folder)
                : VmSetup.prepare(name, GuestOs.LINUX, null, Hypervisor.QEMU, size, listener);
        long started = System.nanoTime();
        vm = VmSetup.install(vm, listener, Duration.ofHours(2));
        say("install took " + Duration.ofNanos(System.nanoTime() - started).toMinutes() + " min");
        assertEquals(VmRecord.Stage.READY, vm.stage());
        assertTrue(!Files.exists(vm.answerIso()), "the answer disc, with the password, is gone");

        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), 30_000)) {
            for (List<String> check : List.of(
                    List.of("/usr/local/bin/legendary", "--version"),
                    List.of("/usr/bin/dpkg-query", "-W", "steam-installer", "tigervnc-standalone-server", "openbox"),
                    List.of("/bin/sh", "-c", "command -v Xvnc xdotool wmctrl; id botmaker; cloud-init status"),
                    List.of("/bin/sh", "-c", "ls /var/lib/cloud/instances /etc/cloud/cloud.cfg.d/99-installer.cfg 2>&1"),
                    List.of("/bin/grep", "BotMaker:", LinuxAutoinstall.SETUP_LOG))) {
                GuestAgent.Ran ran = agent.run(check.getFirst(), check.subList(1, check.size()), Duration.ofMinutes(1));
                say(String.join(" ", check) + " -> " + ran.exitCode() + "\n" + ran.output().strip());
            }
        }
    }

    private static void say(String line) {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + line);
    }
}
