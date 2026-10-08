package com.botmaker.shared.vm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A VMware {@code .vmx}: {@code key = "value"} lines, read and written in the file's own {@code .encoding}.
 * Changing a key rewrites its line in place and every other line is kept as it was, comments and keys this
 * class doesn't know included. Keys compare ignoring case, as VMware's do; a value's {@code "} and {@code |}
 * are VMware's {@code |22} and {@code |7C}.
 */
public final class VmxFile {

    private static final Pattern LINE = Pattern.compile("^\\s*([^=#\\s][^=]*?)\\s*=\\s*\"(.*)\"\\s*$");
    private static final Pattern ESCAPE = Pattern.compile("\\|([0-9A-Fa-f]{2})");

    private final List<String> lines;
    private final Charset charset;

    private VmxFile(List<String> lines, Charset charset) {
        this.lines = new ArrayList<>(lines);
        this.charset = charset;
    }

    /** An empty file in UTF-8, saying so in its first line. */
    public static VmxFile empty() {
        VmxFile vmx = new VmxFile(List.of(), StandardCharsets.UTF_8);
        vmx.set(".encoding", "UTF-8");
        return vmx;
    }

    public static VmxFile read(Path path) throws IOException {
        return parse(Files.readAllBytes(path));
    }

    /** {@code bytes} decoded as their {@code .encoding} line says, else as Windows' Western code page. */
    static VmxFile parse(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            // an editor's UTF-8 byte-order mark, which would hide the .encoding line it starts
            bytes = java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        Charset charset = Charset.forName("windows-1252");
        for (String line : new String(bytes, StandardCharsets.ISO_8859_1).split("\\R")) {
            Matcher m = LINE.matcher(line);
            if (m.matches() && m.group(1).equalsIgnoreCase(".encoding")) {
                try {
                    charset = Charset.forName(m.group(2));
                } catch (RuntimeException e) {
                    // an encoding Java doesn't know: keep the default
                }
            }
        }
        String text = new String(bytes, charset);
        // No -1 limit: the final line break leaves no empty line for a new key to land after.
        return new VmxFile(text.isEmpty() ? List.of() : List.of(text.split("\\R")), charset);
    }

    /** Writes the file in its encoding, with no byte-order mark, which VMware refuses. */
    public void write(Path path) throws IOException {
        Files.write(path, bytes());
    }

    byte[] bytes() {
        List<String> out = new ArrayList<>(lines);
        while (!out.isEmpty() && out.getLast().isEmpty()) out.removeLast();
        return (String.join("\r\n", out) + "\r\n").getBytes(charset);
    }

    /** The value of {@code key}, unescaped, or {@code null} when it has none. */
    public String get(String key) {
        int at = indexOf(key);
        return at < 0 ? null : unescape(value(lines.get(at)));
    }

    /** Sets {@code key}, on its own line where it was, else at the end. */
    public VmxFile set(String key, String value) {
        String line = key + " = \"" + escape(value) + "\"";
        int at = indexOf(key);
        if (at >= 0) lines.set(at, line);
        else lines.add(line);
        return this;
    }

    public VmxFile remove(String key) {
        int at;
        while ((at = indexOf(key)) >= 0) lines.remove(at);
        return this;
    }

    private int indexOf(String key) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = LINE.matcher(lines.get(i));
            if (m.matches() && m.group(1).equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    private static String value(String line) {
        Matcher m = LINE.matcher(line);
        return m.matches() ? m.group(2) : null;
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '|' || c < 0x20) out.append('|').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
            else out.append(c);
        }
        return out.toString();
    }

    static String unescape(String value) {
        return ESCAPE.matcher(value).replaceAll(m -> Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(m.group(1), 16))));
    }

    /**
     * A new Windows 11 VM: EFI with Secure Boot off (the answer file skips Windows' own check), an NVMe disk
     * at {@code disk}, an e1000e network card on NAT (Windows Setup has its driver), SVGA with 3D, the Windows
     * disc then {@code spec.discs()} on SATA, and its screen served over VNC on loopback with {@code
     * vncPassword}. The NVMe disk and the network card sit on the PCIe root ports VMware's own wizard writes.
     *
     * <p>The Windows disc boots first, and its loader waits five seconds for a key before it gives up: whoever
     * starts the VM presses one over VNC. The discs boot before the disk, and the firmware holds
     * {@value #BOOT_DELAY_MS} ms first, so the screen is connected by then: left to itself, VMware's firmware
     * tried the empty disk and the network before the disc, and its prompt came after the key presses had ended,
     * live, leaving the VM in its Boot Manager. Once Windows is installed the discs are taken out, and the disk
     * boots.
     */
    public static VmxFile create(VmSpec spec, String disk, String vncPassword) {
        VmxFile vmx = empty()
                .set("config.version", "8")
                .set("virtualHW.version", "21")
                .set("displayName", spec.name())
                .set("guestOS", "windows11-64")
                .set("firmware", "efi")
                .set("uefi.secureBoot.enabled", "FALSE")
                .set("bios.bootDelay", Integer.toString(BOOT_DELAY_MS))
                .set("bios.bootOrder", "cdrom,hdd")
                .set("memsize", Integer.toString(spec.size().memoryMb()))
                .set("numvcpus", Integer.toString(spec.size().cpus()))
                .set("cpuid.coresPerSocket", Integer.toString(spec.size().cpus()))
                .set("pciBridge0.present", "TRUE")
                .set("nvme0.present", "TRUE")
                .set("nvme0:0.present", "TRUE")
                .set("nvme0:0.fileName", disk)
                .set("sata0.present", "TRUE")
                .set("ethernet0.present", "TRUE")
                .set("ethernet0.connectionType", "nat")
                .set("ethernet0.virtualDev", "e1000e")
                .set("ethernet0.addressType", "generated")
                .set("usb_xhci.present", "TRUE")
                .set("svga.present", "TRUE")
                .set("mks.enable3d", "TRUE")
                .set("svga.graphicsMemoryKB", Integer.toString(Math.min(spec.size().memoryMb() / 2, 8192) * 1024))
                .set("tools.syncTime", "TRUE")
                .set("floppy0.present", "FALSE")
                .set("RemoteDisplay.vnc.enabled", "TRUE")
                .set("RemoteDisplay.vnc.ip", "127.0.0.1")
                .set("RemoteDisplay.vnc.port", Integer.toString(spec.vncPort()))
                .set("RemoteDisplay.vnc.password", vncPassword);
        for (int bridge = 4; bridge <= 7; bridge++) {
            vmx.set("pciBridge" + bridge + ".present", "TRUE")
                    .set("pciBridge" + bridge + ".virtualDev", "pcieRootPort")
                    .set("pciBridge" + bridge + ".functions", "8");
        }
        return vmx.setDiscs(spec);
    }

    /** How long the firmware waits before booting: long enough for the screen to connect. */
    static final int BOOT_DELAY_MS = 10_000;

    /** SATA ports VMware gives a controller. */
    private static final int SATA_PORTS = 30;
    private static final List<String> SLOT_KEYS = List.of(".present", ".deviceType", ".fileName", ".startConnected");

    /**
     * Puts {@code spec}'s discs in the SATA drives, the Windows one first, and empties every other drive: once
     * Windows is installed, that takes the installer and the answer disc out, and with them the firmware's
     * wait and disc-first order, which only the installing boot needs.
     */
    public VmxFile setDiscs(VmSpec spec) {
        if (spec.windowsIso() == null) remove("bios.bootDelay").remove("bios.bootOrder");
        List<Path> discs = new ArrayList<>();
        if (spec.windowsIso() != null) discs.add(spec.windowsIso());
        discs.addAll(spec.discs());
        for (int i = 0; i < SATA_PORTS; i++) {
            String slot = String.format(Locale.ROOT, "sata0:%d", i);
            if (i >= discs.size()) {
                for (String key : SLOT_KEYS) remove(slot + key);
                continue;
            }
            set(slot + ".present", "TRUE")
                    .set(slot + ".deviceType", "cdrom-image")
                    .set(slot + ".fileName", discs.get(i).toString())
                    .set(slot + ".startConnected", "TRUE");
        }
        return this;
    }
}
