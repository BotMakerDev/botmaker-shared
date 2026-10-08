package com.botmaker.shared.vm;

import com.botmaker.shared.tools.Downloads;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What installs a Linux game VM with nobody at the keyboard: Ubuntu Server, which Studio downloads itself
 * ({@link #UBUNTU}), and its answer, a cloud-init {@code CIDATA} disc ({@link #discFiles}) that Ubuntu's installer
 * reads as its autoinstall. The installer asks before it writes the disk unless {@code autoinstall} is on the
 * kernel's command line ({@link #KERNEL_ARGUMENTS}), which QEMU gives the disc's own kernel ({@link Qemu.Kernel}).
 *
 * <p>The installed system has:
 * <ul>
 *   <li>the account {@link VmSetup#GUEST_USER}, with the guest's password and {@code sudo} without one;</li>
 *   <li>the guest tools: QEMU's guest agent, or VMware's open-vm-tools;</li>
 *   <li>what a bot's display needs: TigerVNC's {@code Xvnc}, Openbox, {@code xdotool} and {@code wmctrl};</li>
 *   <li>Steam (with its 32-bit libraries) and Legendary, Epic's launcher for Linux, checked against
 *   {@link #LEGENDARY}'s SHA-256. Its first start installs them, logging to {@value #SETUP_LOG}.</li>
 * </ul>
 * The first start then deletes what holds the password in plain text (the copies of this answer), turns
 * cloud-init off, and writes {@value #READY_FILE}. A launcher that failed to install is in the log, and the VM is
 * ready without it.
 */
public final class LinuxAutoinstall {

    /** Ubuntu Server 24.04.5 LTS's disc, as it is named everywhere it is served. */
    static final String UBUNTU_FILE = "ubuntu-24.04.5-live-server-amd64.iso";
    private static final String UBUNTU_SHA_256 = "97f3d7ffb032c3eb3b23d2c8be9cc76e60c2c1f2c0146ba5ba9fe01cafae0fd8";
    /**
     * Where Ubuntu's disc is, checked against the SHA-256 Canonical publishes beside it: the current releases'
     * folder, then the archive it moves to once the next 24.04 point release replaces it (2026-10: 24.04.1 is
     * there under the same folder name).
     */
    public static final List<Downloads.Remote> UBUNTU = List.of(
            new Downloads.Remote("https://releases.ubuntu.com/24.04/" + UBUNTU_FILE, Downloads.Digest.SHA_256,
                    UBUNTU_SHA_256, 0),
            new Downloads.Remote("https://old-releases.ubuntu.com/releases/24.04/" + UBUNTU_FILE,
                    Downloads.Digest.SHA_256, UBUNTU_SHA_256, 0));
    /** Legendary 0.21.1's Linux build. */
    static final Downloads.Remote LEGENDARY = new Downloads.Remote(
            "https://github.com/legendary-gl/legendary/releases/download/0.21.1/legendary_linux_x64",
            Downloads.Digest.SHA_256, "dbed33bbe96031e65858233e4badf0a1d401bb6cb0cc995d1237cf3a0c826a45", 3_093_622);

    /** The installer's kernel and initial RAM disk, on the Ubuntu disc. */
    static final String KERNEL = "casper/vmlinuz";
    static final String INITRD = "casper/initrd";
    /**
     * {@code autoinstall}: no question before the disk is written. {@code noprompt}: the live system doesn't wait
     * for Enter to have its disc taken out before it restarts.
     */
    static final String KERNEL_ARGUMENTS = "autoinstall noprompt ---";
    /** The answer disc's label, which cloud-init's NoCloud source looks for. */
    static final String LABEL = "CIDATA";
    /** Written at the end of the first start. */
    public static final String READY_FILE = "/var/lib/botmaker/ready";
    static final String SETUP_LOG = "/var/log/botmaker-setup.log";

    private LinuxAutoinstall() {}

    /** The {@code CIDATA} disc's files: {@code user-data} with the autoinstall, and an empty {@code meta-data}. */
    static Map<String, byte[]> discFiles(String user, String password, String hostname, Hypervisor hypervisor) {
        return Map.of("user-data", userData(user, password, hostname, hypervisor).getBytes(StandardCharsets.UTF_8),
                "meta-data", new byte[0]);
    }

    static String userData(String user, String password, String hostname, Hypervisor hypervisor) {
        if (!password.matches("[A-Za-z0-9]+") || !user.matches("[a-z][a-z0-9-]*") || !hostname.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("A user, password and host name of plain letters and digits.");
        }
        List<String> packages = List.of(hypervisor == Hypervisor.VMWARE ? "open-vm-tools" : "qemu-guest-agent",
                "tigervnc-standalone-server", "openbox", "xdotool", "wmctrl", "x11-utils", "curl");
        return "#cloud-config\n"
                + "autoinstall:\n"
                + "  version: 1\n"
                + "  locale: en_US.UTF-8\n"
                + "  keyboard:\n"
                + "    layout: us\n"
                + "  storage:\n"
                + "    layout:\n"
                + "      name: direct\n"
                + "  ssh:\n"
                + "    install-server: false\n"
                + "  packages:\n"
                + packages.stream().map(p -> "    - " + p + "\n").reduce("", String::concat)
                + "  user-data:\n"
                + "    hostname: " + hostname + "\n"
                + "    users:\n"
                + "      - name: " + user + "\n"
                + "        plain_text_passwd: '" + password + "'\n"
                + "        lock_passwd: false\n"
                + "        shell: /bin/bash\n"
                + "        sudo: 'ALL=(ALL) NOPASSWD:ALL'\n"
                + "    write_files:\n"
                + file("/usr/local/sbin/botmaker-setup", setupScript())
                + file("/usr/local/sbin/botmaker-finish", finishScript())
                + "    runcmd:\n"
                + "      - [/usr/local/sbin/botmaker-setup]\n"
                + "  shutdown: reboot\n";
    }

    /** One {@code write_files} entry: an executable script. */
    private static String file(String path, String script) {
        return "      - path: " + path + "\n"
                + "        permissions: '0755'\n"
                + "        content: |\n"
                + script.lines().map(l -> "          " + l + "\n").reduce("", String::concat);
    }

    /**
     * What the first start runs as root: Steam, Wine (Legendary's Windows games, and a Windows program) and
     * Legendary, then {@link #finishScript()} in a unit of its own.
     * Steam's package asks to accept its licence, answered beforehand for both names its question has had.
     */
    static String setupScript() {
        return "#!/bin/sh\n"
                + "exec >>" + SETUP_LOG + " 2>&1\n"
                + "set -x\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "dpkg --add-architecture i386\n"
                + "apt-get update\n"
                + "for owner in steam steam-installer; do\n"
                + "  echo \"$owner steam/question select I AGREE\" | debconf-set-selections\n"
                + "  echo \"$owner steam/license note \" | debconf-set-selections\n"
                + "done\n"
                + "apt-get install -y steam-installer || echo \"BotMaker: Steam didn't install\"\n"
                + LinuxGameCopy.WINE + " || echo \"BotMaker: Wine didn't install\"\n"
                + "if curl -fsSL -o /tmp/legendary '" + LEGENDARY.url() + "'"
                + " && echo '" + LEGENDARY.hex() + "  /tmp/legendary' | sha256sum -c -; then\n"
                + "  install -m 0755 /tmp/legendary /usr/local/bin/legendary\n"
                + "else\n"
                + "  echo \"BotMaker: Legendary didn't install\"\n"
                + "fi\n"
                + "rm -f /tmp/legendary\n"
                + "systemd-run --no-block --unit=botmaker-finish /usr/local/sbin/botmaker-finish\n";
    }

    /**
     * Once cloud-init has finished (it still writes its own files while this answer's commands run, and a unit
     * of its own outlives cloud-init's, whose leftover processes systemd ends): deletes the copies of this answer,
     * cloud-init's and the installer's (its logs and configs, all of {@code /var/log/installer}), turns cloud-init
     * off for later starts, and writes {@value #READY_FILE}.
     */
    static String finishScript() {
        return "#!/bin/sh\n"
                + "exec >>" + SETUP_LOG + " 2>&1\n"
                + "set -x\n"
                + "cloud-init status --wait\n"
                + "rm -f /etc/cloud/cloud.cfg.d/99-installer.cfg\n"
                + "rm -rf /var/log/installer /var/lib/cloud/instances /var/lib/cloud/instance /var/lib/cloud/seed\n"
                + "touch /etc/cloud/cloud-init.disabled\n"
                + "mkdir -p " + READY_FILE.substring(0, READY_FILE.lastIndexOf('/')) + "\n"
                + "touch " + READY_FILE + "\n";
    }

    /** {@code name} as a Linux host name: lower-case letters, digits and hyphens, as Windows' is. */
    static String hostname(String name) {
        return VmSetup.computerName(name).toLowerCase(Locale.ROOT);
    }
}
