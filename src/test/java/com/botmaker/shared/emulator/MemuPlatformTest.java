package com.botmaker.shared.emulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the MEmu {@code .memu} (VirtualBox XML) parser — no MEmu install required. The ADB port is
 * the host side of the NAT forwarding rule whose guest port is 5555.
 */
class MemuPlatformTest {

    private static String memu(String machineName, String forwardings) {
        return "<?xml version=\"1.0\"?>\n"
                + "<VirtualBox>\n"
                + "  <Machine uuid=\"{abc}\" name=\"" + machineName + "\" OSType=\"Linux\">\n"
                + "    <Hardware>\n"
                + "      <Network>\n"
                + "        <Adapter enabled=\"true\">\n"
                + "          <NAT>\n"
                + forwardings
                + "          </NAT>\n"
                + "        </Adapter>\n"
                + "      </Network>\n"
                + "    </Hardware>\n"
                + "  </Machine>\n"
                + "</VirtualBox>\n";
    }

    @Test
    void readsHostPortOfTheGuest5555ForwardingRuleAndMachineName() {
        String xml = memu("MEmu_1", ""
                + "            <Forwarding name=\"misc\" proto=\"1\" hostport=\"21564\" guestport=\"21503\"/>\n"
                + "            <Forwarding name=\"ADB\" proto=\"1\" hostip=\"127.0.0.1\" hostport=\"21563\" guestip=\"\" guestport=\"5555\"/>\n");

        Optional<EmulatorInstance> inst = MemuPlatform.parseVm("MEmu-folder", xml);

        assertTrue(inst.isPresent());
        assertEquals("memu", inst.get().platformId());
        assertEquals("MEmu_1", inst.get().name());          // <Machine name> wins over the folder name
        assertEquals("127.0.0.1", inst.get().host());
        assertEquals(21563, inst.get().adbPort());
    }

    @Test
    void handlesAttributeOrderWithHostportAfterGuestport() {
        String xml = memu("MEmu_2", ""
                + "            <Forwarding name=\"ADB\" guestport=\"5555\" proto=\"1\" hostport=\"21573\"/>\n");

        Optional<EmulatorInstance> inst = MemuPlatform.parseVm("MEmu_2", xml);

        assertTrue(inst.isPresent());
        assertEquals(21573, inst.get().adbPort());
    }

    @Test
    void fallsBackToFolderNameWhenMachineNameMissing() {
        String xml = "<VirtualBox><Machine uuid=\"{x}\">"
                + "<Forwarding name=\"ADB\" hostport=\"21603\" guestport=\"5555\"/>"
                + "</Machine></VirtualBox>";

        Optional<EmulatorInstance> inst = MemuPlatform.parseVm("MEmu_5", xml);

        assertTrue(inst.isPresent());
        assertEquals("MEmu_5", inst.get().name());
        assertEquals(21603, inst.get().adbPort());
    }

    @Test
    void emptyWhenNoAdbForwardingRule() {
        String xml = memu("MEmu_3", ""
                + "            <Forwarding name=\"misc\" proto=\"1\" hostport=\"21564\" guestport=\"21503\"/>\n");

        assertTrue(MemuPlatform.parseVm("MEmu_3", xml).isEmpty());
        assertTrue(MemuPlatform.parseVm("MEmu_3", "<VirtualBox/>").isEmpty());
    }

    @Test
    void attachesMemucStartStopCommandsKeyedByVmFolderName() {
        String xml = memu("Display Name", ""
                + "            <Forwarding name=\"ADB\" hostport=\"21563\" guestport=\"5555\"/>\n");
        EmulatorInstance base = MemuPlatform.parseVm("MEmu_1", xml).orElseThrow();
        Path console = Path.of("C:\\Program Files\\Microvirt\\MEmu\\memuc.exe");
        EmulatorInstance withCmd = MemuPlatform.withLaunch(base, "MEmu_1", console);
        assertEquals(List.of(console.toString(), "start", "-n", "MEmu_1"), withCmd.launchCommand());
        assertEquals(List.of(console.toString(), "stop", "-n", "MEmu_1"), withCmd.stopCommand());
    }
}
