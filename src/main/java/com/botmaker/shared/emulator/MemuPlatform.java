package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Best-effort and Windows-first: no install found / no VMs dir → empty list. The instance name is the title
 * {@code memuc listvms} reports, else the VM's VirtualBox {@code <Machine name="...">}, else the folder name.
 * Checked against MEmu 9.5 ({@code hostport="21503"}).
 */
public final class MemuPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.MEMU;
    private static final String VMS_DIRNAME = "MemuHyperv VMs";

    // A single <Forwarding .../> element (its attributes captured in group 1); order-independent lookups follow.
    private static final Pattern FORWARDING = Pattern.compile("<Forwarding\\b([^>]*)>");
    private static final Pattern GUEST_ADB_PORT = Pattern.compile("guestport=\"5555\"");
    private static final Pattern HOSTPORT = Pattern.compile("hostport=\"(\\d+)\"");
    private static final Pattern MACHINE_NAME = Pattern.compile("<Machine\\b[^>]*\\bname=\"([^\"]*)\"");

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return installDir() != null;
    }

    @Override
    public List<EmulatorInstance> discover() {
        Path install = installDir();
        if (install == null) {
            return List.of();
        }
        Path console = install.resolve("memuc.exe");
        Map<Integer, InstallLocator.ConsoleRow> rows = InstallLocator.list(console, InstallLocator.SYSTEM_CODE_PAGE,
                MemuPlatform::parseListVms, "listvms");
        return PlatformScan.directory(install.resolve(VMS_DIRNAME), dir -> {
            if (!Files.isDirectory(dir)) {
                return Optional.empty();
            }
            String vmName = dir.getFileName().toString();
            Path memu = dir.resolve(vmName + ".memu");
            if (!Files.isReadable(memu)) {
                return Optional.empty();
            }
            Integer index = vmIndex(vmName);
            return parseVm(vmName, Files.readString(memu))
                    .map(base -> index != null && rows.containsKey(index)
                            ? base.withName(rows.get(index).name()).withState(rows.get(index).state()) : base)
                    .map(base -> withLaunch(base, vmName, console));
        });
    }

    /**
     * {@code memuc listvms}, one VM per line: {@code index,title,hwnd,running,pid}; {@code running} is 1 or 0, and
     * says the VM runs, not that Android has booted in it — so starting, and the port says when it is up.
     */
    static Map<Integer, InstallLocator.ConsoleRow> parseListVms(String output) {
        return InstallLocator.csvRows(output, fields -> {
            Long running = InstallLocator.field(fields, 3);
            if (running == null) return EmulatorState.UNKNOWN;
            return running == 1 ? EmulatorState.STARTING : EmulatorState.STOPPED;
        });
    }

    /**
     * The index {@code memuc} numbers a VM folder by: {@code MEmu} is 0, {@code MEmu_3} is 3; {@code null} for a
     * folder named otherwise. {@code memuc listvms} prints {@code index,title,hwnd,running,pid}, so this is how its
     * title finds its folder.
     */
    static Integer vmIndex(String vmName) {
        Matcher m = VM_FOLDER.matcher(vmName);
        if (!m.matches()) return null;
        return m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
    }

    private static final Pattern VM_FOLDER = Pattern.compile("MEmu(?:_(\\d+))?");

    /**
     * {@code <InstallDir>}, or {@code null} if MEmu isn't installed / can't be found: the {@code Microvirt} keys
     * older versions wrote, then the uninstall entry (MEmu 9 writes only that one, naming the {@code Microvirt}
     * folder that holds {@code MEmu\}), then the default folder. The install is the folder holding the VMs.
     */
    private static Path installDir() {
        List<Path> candidates = new ArrayList<>();
        for (String key : List.of("HKLM\\SOFTWARE\\Microvirt\\MEmu", "HKLM\\SOFTWARE\\WOW6432Node\\Microvirt\\MEmu")) {
            candidates.add(InstallLocator.path(WindowsRegistry.read(key, "InstallDir")));
        }
        for (Path folder : InstallLocator.uninstallFolders(
                name -> name.equalsIgnoreCase("MEmu") || name.equalsIgnoreCase("Microvirt"))) {
            candidates.add(folder);
            candidates.add(folder.resolve("MEmu"));
        }
        candidates.add(InstallLocator.programFiles("Microvirt", "MEmu"));
        List<Path> found = InstallLocator.existing(candidates, dir -> Files.isDirectory(dir.resolve(VMS_DIRNAME)));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Attaches MEmu's {@code memuc.exe start/stop -n <vm>} host commands to a parsed instance. The VM's folder
     * name is memuc's {@code -n} selector. Package-private + pure so it's unit-testable; returns {@code base}
     * unchanged when the console is absent.
     */
    static EmulatorInstance withLaunch(EmulatorInstance base, String vmName, Path console) {
        if (console == null) {
            return base;
        }
        String exe = console.toString();
        return base.withCommands(
                List.of(exe, "start", "-n", vmName),
                List.of(exe, "stop", "-n", vmName));
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
}
