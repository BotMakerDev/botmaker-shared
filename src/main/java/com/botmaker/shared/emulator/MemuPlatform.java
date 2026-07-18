package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers <b>MEmu</b> instances. MEmu is VirtualBox-based: the install directory comes from the registry,
 * each instance is a folder under {@code <install>\MemuHyperv VMs\<vm>} containing a {@code <vm>.memu} file
 * (a VirtualBox {@code .vbox} XML), and the ADB port is the host side of the NAT port-forwarding rule that
 * maps to the guest's ADB port 5555:
 *
 * <pre>{@code
 * <Forwarding name="ADB" proto="1" hostip="127.0.0.1" hostport="21563" guestip="" guestport="5555"/>
 * }</pre>
 *
 * <p>Best-effort and Windows-first: no registry key / no VMs dir → empty list. The instance name is the
 * VM's VirtualBox {@code <Machine name="...">} (which MEmu keeps in sync with the multi-instance manager's
 * title), falling back to the folder name.
 *
 * <p>Note: the {@code .memu} forwarding-rule format is the established VirtualBox layout, but this hasn't been
 * verified against a live MEmu install here — treat it like the BlueStacks/LDPlayer parsers (smoke-test on a
 * real machine).
 */
public final class MemuPlatform implements EmulatorPlatform {

    public static final String PLATFORM_ID = "memu";
    private static final String VMS_DIRNAME = "MemuHyperv VMs";

    // A single <Forwarding .../> element (its attributes captured in group 1); order-independent lookups follow.
    private static final Pattern FORWARDING = Pattern.compile("<Forwarding\\b([^>]*)>");
    private static final Pattern GUEST_ADB_PORT = Pattern.compile("guestport=\"5555\"");
    private static final Pattern HOSTPORT = Pattern.compile("hostport=\"(\\d+)\"");
    private static final Pattern MACHINE_NAME = Pattern.compile("<Machine\\b[^>]*\\bname=\"([^\"]*)\"");

    @Override
    public String id() {
        return PLATFORM_ID;
    }

    @Override
    public String displayName() {
        return "MEmu";
    }

    @Override
    public List<EmulatorInstance> discover() {
        Path vmsDir = vmsDir();
        if (vmsDir == null || !Files.isDirectory(vmsDir)) {
            return List.of();
        }
        List<EmulatorInstance> instances = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(vmsDir)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String vmName = dir.getFileName().toString();
                Path memu = dir.resolve(vmName + ".memu");
                if (!Files.isReadable(memu)) {
                    continue;
                }
                try {
                    parseVm(vmName, Files.readString(memu)).ifPresent(instances::add);
                } catch (Exception ignored) {
                    // skip an unreadable/odd VM; keep discovering the rest
                }
            }
        } catch (Exception e) {
            return instances;
        }
        return instances;
    }

    /** {@code <InstallDir>\MemuHyperv VMs}, or {@code null} if MEmu isn't installed / can't be found. */
    private static Path vmsDir() {
        String installDir = firstNonNull(
                WindowsRegistry.read("HKLM\\SOFTWARE\\Microvirt\\MEmu", "InstallDir"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\WOW6432Node\\Microvirt\\MEmu", "InstallDir"));
        if (installDir == null || installDir.isBlank()) {
            return null;
        }
        return Path.of(installDir.trim(), VMS_DIRNAME);
    }

    /**
     * Parses one {@code <vm>.memu} (VirtualBox XML) into an instance. Package-private + pure so it's
     * unit-testable without an MEmu install. Returns empty when there's no ADB (guest 5555) forwarding rule.
     */
    static Optional<EmulatorInstance> parseVm(String vmName, String memuXml) {
        int adbPort = -1;
        Matcher forwarding = FORWARDING.matcher(memuXml);
        while (forwarding.find()) {
            String attrs = forwarding.group(1);
            if (GUEST_ADB_PORT.matcher(attrs).find()) {
                Matcher hostPort = HOSTPORT.matcher(attrs);
                if (hostPort.find()) {
                    adbPort = Integer.parseInt(hostPort.group(1));
                    break;
                }
            }
        }
        if (adbPort < 0) {
            return Optional.empty();
        }
        String name = vmName;
        Matcher machineName = MACHINE_NAME.matcher(memuXml);
        if (machineName.find() && !machineName.group(1).isBlank()) {
            name = machineName.group(1);
        }
        return Optional.of(new EmulatorInstance(PLATFORM_ID, name, "127.0.0.1", adbPort));
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
