package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.emulator.WindowsRegistry;
import com.botmaker.shared.platform.Os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * VMware Workstation, driven through its own command-line tools: {@code vmrun} for a VM's life and the
 * programs run in its guest, {@code vmware-vdiskmanager} for a new disk. Broadcom puts the installer behind a
 * free account sign-in, so the user installs it ({@link #DOWNLOAD_PAGE}) and everything after is automated.
 *
 * <p>{@link #find()} never throws, and is empty off Windows or without the product.
 */
public record VmwareWorkstation(Path folder) {

    /** Where the user gets Workstation; it is free for personal use since 17.5. */
    public static final String DOWNLOAD_PAGE =
            "https://support.broadcom.com/group/ecx/productdownloads?subfamily=VMware+Workstation+Pro";

    private static final List<String> KEYS = List.of(
            "HKLM\\SOFTWARE\\WOW6432Node\\VMware, Inc.\\VMware Workstation",
            "HKLM\\SOFTWARE\\VMware, Inc.\\VMware Workstation");
    private static final Duration QUICK = Duration.ofSeconds(30);
    /** A soft stop waits for the guest to shut down. */
    private static final Duration SLOW = Duration.ofMinutes(3);

    /** The install that has {@code vmrun.exe}, from its registry key or the default folder. */
    public static Optional<VmwareWorkstation> find() {
        if (!Os.current().isWindows()) return Optional.empty();
        List<String> candidates = new ArrayList<>();
        for (String key : KEYS) {
            String dir = WindowsRegistry.read(key, "InstallPath");
            if (dir != null && !dir.isBlank()) candidates.add(dir);
        }
        String x86 = System.getenv("ProgramFiles(x86)");
        if (x86 != null) candidates.add(x86 + "\\VMware\\VMware Workstation");
        for (String dir : candidates) {
            try {
                Path folder = Path.of(dir);
                if (Files.isRegularFile(folder.resolve("vmrun.exe"))) return Optional.of(new VmwareWorkstation(folder));
            } catch (RuntimeException e) {
                // a registry value that is no path
            }
        }
        return Optional.empty();
    }

    public Path vmrun() {
        return folder.resolve("vmrun.exe");
    }

    public Path diskManager() {
        return folder.resolve("vmware-vdiskmanager.exe");
    }

    /** The VMware Tools installer disc for Windows guests, which Workstation ships. */
    public Path toolsIso() {
        return folder.resolve("windows.iso");
    }

    /** Starts {@code vmx} without Workstation's window. */
    public Spawn.Completed start(Path vmx) throws IOException, InterruptedException {
        return Commands.run(SLOW, startCommand(vmx));
    }

    /** Asks the guest to shut down, and waits for it. */
    public Spawn.Completed stop(Path vmx) throws IOException, InterruptedException {
        return Commands.run(SLOW, stopCommand(vmx));
    }

    /** The {@code .vmx} files of the VMs running now. */
    public List<Path> running() throws IOException, InterruptedException {
        Spawn.Completed listed = Commands.run(QUICK, vmrun().toString(), "-T", "ws", "list");
        if (!listed.ok()) throw new IOException("vmrun list failed: " + listed.output().strip());
        return parseList(listed.output());
    }

    /** Whether VMware Tools runs in the guest, which is what a program run in it needs. */
    public boolean toolsRunning(Path vmx) throws IOException, InterruptedException {
        Spawn.Completed state = Commands.run(QUICK, vmrun().toString(), "-T", "ws", "checkToolsState", vmx.toString());
        return state.ok() && state.output().strip().equalsIgnoreCase("running");
    }

    /**
     * Starts {@code program} in the guest, on the signed-in user's desktop, and returns without waiting for it.
     * The guest account's password goes on vmrun's command line; it is the guest's own local account, which
     * only BotMaker uses.
     */
    public Spawn.Completed runInGuest(Path vmx, String user, String password, String program, String arguments)
            throws IOException, InterruptedException {
        return Commands.run(QUICK, runInGuestCommand(vmx, user, password, program, arguments));
    }

    /**
     * Runs {@code program} in the guest, without a desktop, and waits up to {@code timeout} for it; the result's
     * exit code is the program's, read from vmrun's "Guest program exited with non-zero exit code: N", as vmrun
     * exits with its own code then.
     */
    public Spawn.Completed runInGuestAndWait(Path vmx, String user, String password, String program,
                                             List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(vmrun().toString(), "-T", "ws", "-gu", user, "-gp", password,
                "runProgramInGuest", vmx.toString(), program));
        if (!arguments.isEmpty()) command.add(String.join(" ", arguments));
        return guestExitCode(Commands.run(timeout, command));
    }

    private static final java.util.regex.Pattern GUEST_EXIT =
            java.util.regex.Pattern.compile("non-zero exit code:\\s*(-?\\d+)");

    /** {@code done} with the guest program's exit code where vmrun reported one. */
    static Spawn.Completed guestExitCode(Spawn.Completed done) {
        java.util.regex.Matcher m = GUEST_EXIT.matcher(done.output());
        return m.find() ? new Spawn.Completed(Integer.parseInt(m.group(1)), done.output()) : done;
    }

    /** Whether {@code path} exists in the guest; needs VMware Tools running there. */
    public boolean fileExistsInGuest(Path vmx, String user, String password, String path)
            throws IOException, InterruptedException {
        Spawn.Completed found = Commands.run(QUICK, fileExistsCommand(vmx, user, password, path));
        return found.ok() && found.output().contains("The file exists");
    }

    List<String> fileExistsCommand(Path vmx, String user, String password, String path) {
        return List.of(vmrun().toString(), "-T", "ws", "-gu", user, "-gp", password, "fileExistsInGuest",
                vmx.toString(), path);
    }

    /** Creates a growable disk of {@code gigabytes} at {@code vmdk}. */
    public Spawn.Completed createDisk(Path vmdk, int gigabytes) throws IOException, InterruptedException {
        return Commands.run(QUICK, createDiskCommand(vmdk, gigabytes));
    }

    List<String> startCommand(Path vmx) {
        return List.of(vmrun().toString(), "-T", "ws", "start", vmx.toString(), "nogui");
    }

    List<String> stopCommand(Path vmx) {
        return List.of(vmrun().toString(), "-T", "ws", "stop", vmx.toString(), "soft");
    }

    List<String> runInGuestCommand(Path vmx, String user, String password, String program, String arguments) {
        List<String> command = new ArrayList<>(List.of(vmrun().toString(), "-T", "ws", "-gu", user, "-gp", password,
                "runProgramInGuest", vmx.toString(), "-noWait", "-activeWindow", "-interactive", program));
        if (arguments != null && !arguments.isBlank()) command.add(arguments);
        return List.copyOf(command);
    }

    /** {@code -a lsilogic}: the tool knows no NVMe, and says to pass lsilogic for any other adapter. */
    List<String> createDiskCommand(Path vmdk, int gigabytes) {
        return List.of(diskManager().toString(), "-c", "-s", gigabytes + "GB", "-a", "lsilogic", "-t", "0",
                vmdk.toString());
    }

    /** The paths in {@code vmrun list}'s output, after its {@code Total running VMs: N} line. */
    static List<Path> parseList(String output) {
        List<Path> paths = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String path = line.strip();
            if (path.isEmpty() || path.startsWith("Total running VMs")) continue;
            try {
                paths.add(Path.of(path));
            } catch (RuntimeException e) {
                // not a path: a warning vmrun printed
            }
        }
        return List.copyOf(paths);
    }
}
