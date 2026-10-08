package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The answer file, read as Windows Setup reads it: as XML, pass by pass. */
class GuestUnattendTest {

    private static final String NS = "urn:schemas-microsoft-com:unattend";

    @Test
    void eachPassCarriesWhatAnUnattendedInstallNeeds() throws Exception {
        Document xml = parse(GuestUnattend.xml("botmaker", "pw<&\"x", "BOTMAKER-VM", "fr-FR", Hypervisor.QEMU));

        assertEquals(List.of("windowsPE", "specialize", "oobeSystem"), passes(xml));
        List<String> pe = texts(xml, "Path");
        assertTrue(pe.stream().anyMatch(p -> p.contains("LabConfig /v BypassTPMCheck")), pe.toString());
        assertTrue(pe.stream().anyMatch(p -> p.contains("LabConfig /v BypassSecureBootCheck")));
        assertTrue(pe.stream().anyMatch(p -> p.contains("BypassNRO")));
        assertEquals("true", texts(xml, "WillWipeDisk").getFirst());
        assertEquals(GuestUnattend.PRO_INSTALL_KEY, texts(xml, "Key").getFirst());
        assertEquals(List.of("fr-FR"), texts(xml, "SetupUILanguage").stream().map(String::strip).toList());
        assertEquals("BOTMAKER-VM", texts(xml, "ComputerName").getFirst());

        assertEquals(List.of("botmaker"), texts(xml, "Name"), "the local account");
        assertEquals(List.of("botmaker"), texts(xml, "Username"), "who signs in by itself");
        assertEquals(List.of("pw<&\"x", "pw<&\"x"), texts(xml, "Value"), "the password survives XML escaping");
        assertEquals("true", texts(xml, "HideOnlineAccountScreens").getFirst());
        assertEquals("true", texts(xml, "Enabled").getFirst(), "signs in by itself");
    }

    @Test
    void theFirstSignInInstallsTheHypervisorsToolsAndTheLaunchTaskThenSaysReady() throws Exception {
        List<String> qemu = texts(parse(GuestUnattend.xml("botmaker", "p", "VM", "en-US", Hypervisor.QEMU)),
                "CommandLine");
        assertTrue(qemu.stream().anyMatch(c -> c.contains("virtio-win-guest-tools.exe /install /quiet")), qemu.toString());
        assertTrue(qemu.stream().noneMatch(c -> c.contains("setup64.exe")));
        assertTrue(qemu.stream().anyMatch(c -> c.startsWith("schtasks /Create /TN \"BotMaker launch\"")
                && c.contains("/IT") && !c.contains("/RU")));
        assertTrue(qemu.getLast().contains(GuestUnattend.READY_FILE), "ready is written last");

        List<String> vmware = GuestUnattend.firstLogonCommands(Hypervisor.VMWARE);
        String tools = vmware.stream().filter(c -> c.contains("VMwareToolsUpgrader.exe")).findFirst().orElseThrow();
        assertTrue(tools.contains("setup64.exe /S /v \"/qn REBOOT=R\"") && tools.contains("setup.exe /S /v \"/qn REBOOT=R\""),
                "older Tools discs name it setup64.exe, current ones setup.exe: " + tools);

        Document orders = parse(GuestUnattend.xml("botmaker", "p", "VM", "en-US", Hypervisor.VMWARE));
        NodeList commands = orders.getElementsByTagNameNS(NS, "SynchronousCommand");
        for (int i = 0; i < commands.getLength(); i++) {
            Element order = (Element) ((Element) commands.item(i)).getElementsByTagNameNS(NS, "Order").item(0);
            assertEquals(Integer.toString(i + 1), order.getTextContent());
        }
    }

    @Test
    void vmwareStartsTheLaunchTaskAndCreatesItInAVmThatLacksIt() {
        assertEquals("""
                schtasks /Query /TN 'BotMaker launch' *> $null
                if ($LASTEXITCODE -ne 0) { schtasks /Create /TN "BotMaker launch" /TR "C:\\BotMaker\\launch.cmd" /SC ONCE \
                /ST 00:00 /IT /RL HIGHEST /F | Out-Null }
                Set-Content -Path 'C:\\BotMaker\\launch.pending' -Value ''
                schtasks /Run /TN 'BotMaker launch'
                exit $LASTEXITCODE
                """, GuestUnattend.launchTaskScript());
    }

    @Test
    void namesWindowsWouldRefuseAreRefusedHere() {
        assertThrows(IllegalArgumentException.class,
                () -> GuestUnattend.xml("bot maker", "p", "VM", "en-US", Hypervisor.QEMU));
        assertThrows(IllegalArgumentException.class,
                () -> GuestUnattend.xml("botmaker", "p", "A-NAME-LONGER-THAN-15", "en-US", Hypervisor.QEMU));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> passes(Document xml) {
        List<String> passes = new ArrayList<>();
        NodeList settings = xml.getElementsByTagNameNS(NS, "settings");
        for (int i = 0; i < settings.getLength(); i++) passes.add(((Element) settings.item(i)).getAttribute("pass"));
        return passes;
    }

    private static List<String> texts(Document xml, String tag) {
        List<String> texts = new ArrayList<>();
        NodeList nodes = xml.getElementsByTagNameNS(NS, tag);
        for (int i = 0; i < nodes.getLength(); i++) texts.add(nodes.item(i).getTextContent());
        return texts;
    }
}
