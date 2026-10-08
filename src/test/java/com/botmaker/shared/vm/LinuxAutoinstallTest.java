package com.botmaker.shared.vm;

import com.botmaker.shared.tools.Downloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Linux game VM's unattended install: its answer disc, and the installer's kernel off the Ubuntu disc. */
class LinuxAutoinstallTest {

    @Test
    @SuppressWarnings("unchecked")
    void theAnswerIsAnAutoinstallThatMakesTheAccountAndInstallsWhatABotsDisplayNeeds() {
        String text = LinuxAutoinstall.userData("botmaker", "Secret42", "game-vm", Hypervisor.QEMU);
        assertTrue(text.startsWith("#cloud-config\n"), "cloud-init reads only a file that says what it is");
        Map<String, Object> autoinstall = (Map<String, Object>) new Yaml().<Map<String, Object>>load(text).get("autoinstall");
        assertEquals(1, autoinstall.get("version"));
        assertEquals(Map.of("name", "direct"), ((Map<String, Object>) autoinstall.get("storage")).get("layout"));
        List<String> packages = (List<String>) autoinstall.get("packages");
        assertTrue(packages.containsAll(List.of("qemu-guest-agent", "tigervnc-standalone-server", "openbox", "xdotool")),
                packages.toString());
        assertEquals("reboot", autoinstall.get("shutdown"));

        Map<String, Object> userData = (Map<String, Object>) autoinstall.get("user-data");
        assertEquals("game-vm", userData.get("hostname"));
        Map<String, Object> user = ((List<Map<String, Object>>) userData.get("users")).getFirst();
        assertEquals("botmaker", user.get("name"));
        assertEquals("Secret42", user.get("plain_text_passwd"));
        assertEquals(false, user.get("lock_passwd"));
        List<Map<String, Object>> files = (List<Map<String, Object>>) userData.get("write_files");
        assertEquals(LinuxAutoinstall.setupScript(), files.get(0).get("content"));
        assertEquals(LinuxAutoinstall.finishScript(), files.get(1).get("content"));
        assertEquals(List.of(List.of("/usr/local/sbin/botmaker-setup")), userData.get("runcmd"));

        assertTrue(((List<String>) new Yaml().<Map<String, Map<String, Object>>>load(
                LinuxAutoinstall.userData("botmaker", "x", "g", Hypervisor.VMWARE)).get("autoinstall").get("packages"))
                .contains("open-vm-tools"));
        assertThrows(IllegalArgumentException.class,
                () -> LinuxAutoinstall.userData("botmaker", "it's", "g", Hypervisor.QEMU));
    }

    @Test
    void theFirstStartInstallsSteamAndACheckedLegendaryThenCleansUpAndSaysItIsReady() {
        String setup = LinuxAutoinstall.setupScript();
        assertTrue(setup.contains("dpkg --add-architecture i386"), setup);
        assertTrue(setup.contains("apt-get install -y steam-installer"), setup);
        assertTrue(setup.contains("echo '" + LinuxAutoinstall.LEGENDARY.hex() + "  /tmp/legendary' | sha256sum -c -"), setup);
        assertTrue(setup.endsWith("systemd-run --no-block --unit=botmaker-finish /usr/local/sbin/botmaker-finish\n"));
        String finish = LinuxAutoinstall.finishScript();
        assertTrue(finish.contains("cloud-init status --wait\n"), finish);
        assertTrue(finish.contains("rm -f /etc/cloud/cloud.cfg.d/99-installer.cfg"), "the password's copies go");
        assertTrue(finish.contains("rm -rf /var/log/installer "), finish);
        assertTrue(finish.endsWith("touch " + LinuxAutoinstall.READY_FILE + "\n"), finish);
        assertTrue(LinuxAutoinstall.UBUNTU.stream().allMatch(Downloads.Remote::wellFormed)
                && LinuxAutoinstall.LEGENDARY.wellFormed());
        assertTrue(LinuxAutoinstall.UBUNTU.getLast().url().startsWith("https://old-releases.ubuntu.com/releases/24.04/"));
        assertEquals("game-vm-1", LinuxAutoinstall.hostname("Game VM_1"));
    }

    @Test
    void theAnswerDiscIsLabelledForCloudInit() {
        Map<String, byte[]> files = LinuxAutoinstall.discFiles("botmaker", "Secret42", "g", Hypervisor.QEMU);
        assertEquals(java.util.Set.of("user-data", "meta-data"), files.keySet());
        assertEquals(0, files.get("meta-data").length);
        assertEquals("CIDATA", LinuxAutoinstall.LABEL);
    }

    @Test
    void aFileIsCopiedOffADiscImageByItsName(@TempDir Path dir) throws IOException {
        byte[] kernel = "a kernel".repeat(1000).getBytes(StandardCharsets.US_ASCII);
        Path iso = dir.resolve("disc.iso");
        IsoImage.write(iso, "UBUNTU", Map.of("vmlinuz", kernel, "initrd", new byte[] {1, 2, 3}));
        Path out = dir.resolve("k");
        IsoFiles.copy(iso, "vmlinuz", out);
        assertArrayEquals(kernel, Files.readAllBytes(out));
        assertThrows(IOException.class, () -> IsoFiles.copy(iso, "casper/vmlinuz", dir.resolve("x")));
        assertEquals("vmlinuz", IsoFiles.normal("VMLINUZ.;1"));
        Files.write(dir.resolve("not.iso"), new byte[40_000]);
        assertThrows(IOException.class, () -> IsoFiles.copy(dir.resolve("not.iso"), "vmlinuz", out));
    }
}
