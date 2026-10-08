package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A {@code .vmx} edited the way VMware writes one: in place, in its encoding, every other line kept. */
class VmxFileTest {

    @Test
    void changingAKeyKeepsEveryOtherLineAndItsPlace() {
        String original = """
                .encoding = "UTF-8"
                # a comment VMware never wrote
                displayName = "Jeu été"
                memsize = "4096"
                something.unknown = "kept"
                """;
        VmxFile vmx = VmxFile.parse(original.getBytes(StandardCharsets.UTF_8));
        assertEquals("Jeu été", vmx.get("DISPLAYNAME"), "keys ignore case");
        vmx.set("MemSize", "8192").set("RemoteDisplay.vnc.port", "5901").remove("something.unknown");

        String written = new String(vmx.bytes(), StandardCharsets.UTF_8);
        assertEquals(List.of(".encoding = \"UTF-8\"", "# a comment VMware never wrote", "displayName = \"Jeu été\"",
                "MemSize = \"8192\"", "RemoteDisplay.vnc.port = \"5901\""), written.lines().toList());
        assertNotEquals('\uFEFF', written.charAt(0), "no byte-order mark");
        assertEquals("\r\n", written.substring(written.length() - 2));
    }

    @Test
    void aFileWithNoEncodingLineIsReadAsWindowsWestern() {
        byte[] bytes = "displayName = \"Jeu été\"\n".getBytes(Charset.forName("windows-1252"));
        VmxFile vmx = VmxFile.parse(bytes);
        assertEquals("Jeu été", vmx.get("displayName"));
        vmx.set("displayName", "Jeu à");
        assertEquals("displayName = \"Jeu à\"\r\n", new String(vmx.bytes(), Charset.forName("windows-1252")));
    }

    @Test
    void aByteOrderMarkDoesNotHideTheEncoding() {
        byte[] text = ".encoding = \"UTF-8\"\ndisplayName = \"été\"\n".getBytes(StandardCharsets.UTF_8);
        byte[] marked = new byte[text.length + 3];
        marked[0] = (byte) 0xEF;
        marked[1] = (byte) 0xBB;
        marked[2] = (byte) 0xBF;
        System.arraycopy(text, 0, marked, 3, text.length);
        VmxFile vmx = VmxFile.parse(marked);
        assertEquals("été", vmx.get("displayName"));
        assertArrayEquals(text, new String(vmx.bytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")
                .getBytes(StandardCharsets.UTF_8), "written back without the mark");
    }

    @Test
    void quotesAndBarsAreVmwaresEscapes() {
        assertEquals("a|22b|7Cc", VmxFile.escape("a\"b|c"));
        assertEquals("a\"b|c", VmxFile.unescape("a|22b|7Cc"));
        VmxFile vmx = VmxFile.empty().set("x", "say \"hi\"");
        assertEquals("say \"hi\"", VmxFile.parse(vmx.bytes()).get("x"));
        assertNull(vmx.get("missing"));
    }

    @Test
    void aNewVmBootsEfiFromItsDiscsAndServesVncOnLoopback() {
        VmSpec spec = new VmSpec("Game VM", Path.of("vm"), new VmSize(4, 8192, 80), Path.of("C:\\isos\\win11.iso"),
                List.of(Path.of("answer.iso"), Path.of("C:\\VMware\\windows.iso")), 5901);
        VmxFile vmx = VmxFile.create(spec, "disk.vmdk", "abcd1234");

        assertEquals("UTF-8", vmx.get(".encoding"));
        assertEquals("efi", vmx.get("firmware"));
        assertEquals("FALSE", vmx.get("uefi.secureBoot.enabled"));
        assertEquals("8192", vmx.get("memsize"));
        assertEquals("4", vmx.get("numvcpus"));
        assertEquals("disk.vmdk", vmx.get("nvme0:0.fileName"));
        assertEquals("e1000e", vmx.get("ethernet0.virtualDev"));
        assertEquals("TRUE", vmx.get("mks.enable3d"));
        assertEquals(Integer.toString(4096 * 1024), vmx.get("svga.graphicsMemoryKB"), "half the guest's memory");
        assertEquals("pcieRootPort", vmx.get("pciBridge4.virtualDev"));
        assertEquals("TRUE", vmx.get("pciBridge7.present"));
        assertEquals("C:\\isos\\win11.iso", vmx.get("sata0:0.fileName"));
        assertEquals("answer.iso", vmx.get("sata0:1.fileName"));
        assertEquals("C:\\VMware\\windows.iso", vmx.get("sata0:2.fileName"));
        assertEquals("cdrom-image", vmx.get("sata0:2.deviceType"));
        assertEquals("127.0.0.1", vmx.get("RemoteDisplay.vnc.ip"));
        assertEquals("5901", vmx.get("RemoteDisplay.vnc.port"));
        assertEquals("abcd1234", vmx.get("RemoteDisplay.vnc.password"));

        VmSpec installed = new VmSpec("Game VM", Path.of("vm"), new VmSize(4, 8192, 80), null, List.of(), 5901);
        vmx.setDiscs(installed);
        assertNull(vmx.get("sata0:0.fileName"), "the installer is out");
        assertNull(vmx.get("sata0:1.present"), "and the answer disc");
        assertNull(vmx.get("sata0:2.deviceType"));
        assertEquals("TRUE", vmx.get("sata0.present"), "the controller stays");
    }
}
