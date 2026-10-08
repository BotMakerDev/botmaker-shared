package com.botmaker.shared.vm;

import com.botmaker.shared.tools.UserDirs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    Path.of("C:\\isos\\Win 11.iso"), "fr-FR", 5901, 40001, 40002);
            saved.save();
            assertEquals(saved, VmRecord.load(folder));
            assertEquals(List.of(saved), VmInventory.list());
            assertTrue(VmInventory.portsInUse().containsAll(List.of(5901, 40001, 40002)));
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
                Path.of("win.iso"), "en-US", 5900, 40001, 40002);
        VmSpec installing = vm.spec(List.of(Path.of("tools.iso")));
        assertEquals(Path.of("win.iso"), installing.windowsIso());
        assertEquals(List.of(vm.answerIso(), Path.of("tools.iso")), installing.discs());

        VmSpec ready = vm.withStage(VmRecord.Stage.READY).spec(List.of(Path.of("tools.iso")));
        assertNull(ready.windowsIso());
        assertEquals(List.of(), ready.discs());
        assertEquals(VmRecord.Stage.UNKNOWN, VmRecord.Stage.fromId("later"));
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
}
