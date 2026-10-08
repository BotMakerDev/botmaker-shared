package com.botmaker.shared.vm;

import com.botmaker.shared.tools.UserDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of setting a VM up that need no hypervisor: its record, its names, the disc's language. */
class VmSetupTest {

    @Test
    void aRecordComesBackAsSavedAndListsInTheInventory(@TempDir Path config) throws Exception {
        String before = System.getProperty(UserDirs.CONFIG_PROPERTY);
        System.setProperty(UserDirs.CONFIG_PROPERTY, config.toString());
        try {
            Path folder = VmInventory.folder("Game VM");
            Files.createDirectories(folder);
            VmRecord saved = new VmRecord(folder, "Game VM", Hypervisor.QEMU, VmRecord.Stage.PREPARED, new VmSize(2, 6144, 80),
                    Path.of("C:\\isos\\Win 11.iso"), "fr-FR", 5901, 40001, 40002, 40003, GuestOs.LINUX);
            saved.save();
            assertEquals(saved, VmRecord.load(folder));
            String text = Files.readString(folder.resolve(VmRecord.FILE));
            Files.writeString(folder.resolve(VmRecord.FILE), text.replaceAll("(?m)^guestOs=.*\\R", ""));
            assertEquals(GuestOs.WINDOWS, VmRecord.load(folder).guestOs(), "a record from before Linux VMs");
            saved.save();
            assertEquals(List.of(saved), VmInventory.list());
            assertTrue(VmInventory.portsInUse().containsAll(List.of(5901, 40001, 40002, 40003)));
            assertTrue(VmSetup.freeVncPort() != 5901, "a port another VM has is never handed out again");

            Files.createDirectories(VmInventory.folder("broken"));
            Files.writeString(VmInventory.folder("broken").resolve(VmRecord.FILE), "name=x");
            assertEquals(List.of(saved), VmInventory.list(), "a damaged record isn't listed");
        } finally {
            if (before == null) System.clearProperty(UserDirs.CONFIG_PROPERTY);
            else System.setProperty(UserDirs.CONFIG_PROPERTY, before);
        }
    }

    @Test
    void whileInstallingItCarriesItsDiscsAndOnceReadyNone() {
        VmRecord vm = new VmRecord(Path.of("vm"), "g", Hypervisor.QEMU, VmRecord.Stage.INSTALLING, new VmSize(2, 4096, 80),
                Path.of("win.iso"), "en-US", 5900, 40001, 40002, 40003, GuestOs.WINDOWS);
        VmSpec installing = vm.spec(List.of(Path.of("tools.iso")));
        assertEquals(Path.of("win.iso"), installing.installIso());
        assertEquals(List.of(vm.answerIso(), Path.of("tools.iso")), installing.discs());

        VmSpec ready = vm.withStage(VmRecord.Stage.READY).spec(List.of(Path.of("tools.iso")));
        assertNull(ready.installIso());
        assertEquals(List.of(), ready.discs());
        assertEquals(VmRecord.Stage.UNKNOWN, VmRecord.Stage.fromId("later"));
    }

    @Test
    void aLinuxVmBootsItsInstallerUntilTheInstallerHasRestartedIt(@TempDir Path folder) throws Exception {
        VmRecord linux = new VmRecord(folder, "g", Hypervisor.QEMU, VmRecord.Stage.INSTALLING, new VmSize(2, 4096, 64),
                Path.of("ubuntu.iso"), "en-US", 5900, 40001, 40002, 40003, GuestOs.LINUX);
        Qemu.Kernel kernel = VmSetup.installerKernel(linux).orElseThrow();
        assertEquals(folder.resolve(VmSetup.INSTALLER_KERNEL), kernel.kernel());
        assertEquals(LinuxAutoinstall.KERNEL_ARGUMENTS, kernel.arguments());
        try (var file = new java.io.RandomAccessFile(linux.disk().toFile(), "rw")) {
            file.setLength(2L * 1024 * 1024 * 1024);
        }
        assertTrue(VmSetup.installerKernel(linux).isPresent(), "a disk half written by a start that ended");

        Files.writeString(folder.resolve(VmSetup.INSTALLER_DONE), "");
        assertTrue(VmSetup.installerKernel(linux).isEmpty(), "the installed system boots");
        Files.delete(folder.resolve(VmSetup.INSTALLER_DONE));
        assertTrue(VmSetup.installerKernel(linux.withStage(VmRecord.Stage.READY)).isEmpty());
        assertTrue(VmSetup.installerKernel(new VmRecord(folder, "g", Hypervisor.QEMU, VmRecord.Stage.INSTALLING,
                new VmSize(2, 4096, 64), Path.of("win.iso"), "en-US", 5900, 40001, 40002, 40003, GuestOs.WINDOWS))
                .isEmpty(), "Windows boots its own disc");
        assertEquals(List.of("A Linux game VM runs on QEMU for now."),
                VmSetup.problems(GuestOs.LINUX, Hypervisor.VMWARE, null, new VmSize(2, 4096, 64)).stream()
                        .filter(p -> p.contains("Linux")).toList());
    }

    @Test
    void theDiscsKeyIsPressedOnlyWhileTheDiskIsEmpty(@TempDir Path folder) throws Exception {
        VmRecord vm = new VmRecord(folder, "g", Hypervisor.QEMU, VmRecord.Stage.INSTALLING, new VmSize(2, 4096, 64),
                Path.of("win.iso"), "en-US", 5900, 40001, 40002, 40003, GuestOs.WINDOWS);
        assertTrue(!VmSetup.diskWritten(vm), "no disk yet");
        Files.write(vm.disk(), new byte[200 * 1024]);
        assertTrue(!VmSetup.diskWritten(vm), "a new qcow2 is a few hundred kilobytes");
        try (var file = new java.io.RandomAccessFile(vm.disk().toFile(), "rw")) {
            file.setLength(78L * 1024 * 1024);
        }
        assertTrue(!VmSetup.diskWritten(vm), "a new VMware disk is already 78 MB of tables");
        try (var file = new java.io.RandomAccessFile(vm.disk().toFile(), "rw")) {
            file.setLength(600L * 1024 * 1024);
        }
        assertTrue(VmSetup.diskWritten(vm), "Setup has partitioned it: the disc would start over");
    }

    @Test
    void theDiscsLanguageIsTheFirstItOffers() {
        assertEquals("fr-FR", VmSetup.languageOf("""
                [Available UI Languages]
                fr-FR = 3

                [Fallback Languages]
                en-US = en-us
                """));
        assertEquals("en-US", VmSetup.languageOf("nothing here"));
        assertEquals("en-US", VmSetup.languageOf("[Available UI Languages]\r\nen-us = 3\r\n"), "as Windows writes it");
    }

    @Test
    void aComputerNameIsWhatWindowsAccepts() {
        assertEquals("Game-VM", VmSetup.computerName("Game VM"));
        assertEquals("a-very-long-nam", VmSetup.computerName("a very long name indeed"));
        assertEquals("BOTMAKER-123", VmSetup.computerName("123"));
        assertEquals("BOTMAKER", VmSetup.computerName("__"));
    }

    @Test
    void theDiscsPromptIsALineAtTheTopAndNothingElse() {
        BufferedImage prompt = screen();
        paint(prompt, 32, 18, 480, 16); // "Press any key to boot from CD or DVD...."
        assertTrue(VmSetup.looksLikeBootPrompt(prompt));

        BufferedImage splash = screen();
        paint(splash, 290, 350, 450, 70); // the firmware's logo, in the middle
        assertFalse(VmSetup.looksLikeBootPrompt(splash));

        paint(prompt, 200, 190, 340, 16); // the Boot Manager's menu, under its title
        assertFalse(VmSetup.looksLikeBootPrompt(prompt));
        assertFalse(VmSetup.looksLikeBootPrompt(screen()), "a black screen says nothing yet");
    }

    @Test
    void theKeysEndOnceThePromptHasBeenGoneForTwoFrames() {
        BufferedImage prompt = screen();
        paint(prompt, 32, 18, 480, 16);
        BufferedImage black = screen();

        VmSetup.PromptKeys keys = new VmSetup.PromptKeys();
        assertFalse(keys.done(black), "before the prompt, the keys go on");
        assertFalse(keys.done(null));
        assertFalse(keys.done(prompt));
        assertFalse(keys.done(black), "one frame without it may be a redraw");
        assertFalse(keys.done(prompt));
        assertFalse(keys.done(black));
        assertFalse(keys.done(null), "no frame counts for nothing");
        assertTrue(keys.done(black), "Setup's screen would come next, with Cancel focused");
    }

    private static BufferedImage screen() {
        return new BufferedImage(1024, 768, BufferedImage.TYPE_INT_RGB);
    }

    private static void paint(BufferedImage image, int x, int y, int w, int h) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xB0, 0x00, 0xB0));
        g.fillRect(x, y, w, h);
        g.dispose();
    }
}
