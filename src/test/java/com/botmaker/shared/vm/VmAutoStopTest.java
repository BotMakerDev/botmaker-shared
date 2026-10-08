package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A VM's own shutdown settings, and the watcher that shuts it down once Studio has gone. */
class VmAutoStopTest {

    private static VmRecord vm(Path folder) {
        return new VmRecord(folder, "g", Hypervisor.QEMU, VmRecord.Stage.READY, new VmSize(2, 4096, 64),
                Path.of("win.iso"), "en-US", 5900, 40001, 40002, 40003);
    }

    @Test
    void offUntilSavedAndBackAsSaved(@TempDir Path folder) throws Exception {
        VmRecord vm = vm(folder);
        assertEquals(VmAutoStop.OFF, VmAutoStop.load(vm));
        new VmAutoStop(true, 30).save(vm);
        assertEquals(new VmAutoStop(true, 30), VmAutoStop.load(vm));

        Files.writeString(folder.resolve(VmAutoStop.FILE), "idleMinutes=soon");
        assertEquals(VmAutoStop.OFF, VmAutoStop.load(vm), "a damaged file is off, never a throw");
        assertThrows(IllegalArgumentException.class, () -> new VmAutoStop(false, -1));
    }

    @Test
    void theWatcherWaitsForStudioThenPressesThePowerButtonAndLaterPullsThePlug(@TempDir Path folder) {
        String script = VmAutoStop.watchScript(vm(folder), 1234).orElseThrow();
        assertTrue(script.startsWith("Wait-Process -Id 1234 "), script);
        assertTrue(script.contains("TcpClient('127.0.0.1', 40001)"), "the VM's command port, not its events'");
        assertTrue(script.indexOf("Qmp 'system_powerdown'") < script.indexOf("Qmp 'quit'"), script);
        assertTrue(script.contains("AddMinutes(3)"), "Windows gets as long as Shut down gives it");
    }

    @Test
    void anUnknownHypervisorHasNoWatcher(@TempDir Path folder) {
        VmRecord unknown = new VmRecord(folder, "g", Hypervisor.UNKNOWN, VmRecord.Stage.READY, new VmSize(2, 4096, 64),
                Path.of("win.iso"), "en-US", 5900, 0, 0, 0);
        assertTrue(VmAutoStop.watchScript(unknown, 1).isEmpty());
        assertTrue(!VmAutoStop.unwatched(unknown), "never idle: nothing to ask");
    }
}
