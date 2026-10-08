package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.emulator.WindowsRegistry;
import com.botmaker.shared.platform.Os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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

    /** Asks the guest to shut down and waits for it; {@code hard} powers it off at once instead. */
    public Spawn.Completed stop(Path vmx, boolean hard) throws IOException, InterruptedException {
        return Commands.run(SLOW, stopCommand(vmx, hard));
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
     * Runs PowerShell {@code script} in the guest, without a desktop, and waits up to {@code timeout} for it.
     * The result is the script's exit code and what it printed; a failure of vmrun itself, or the timeout, is
     * vmrun's. The script goes into the guest as a file and its output comes back as one: PowerShell started
     * straight by vmrun has no output to write to and exits 1 at once, whatever the script, live, so {@code cmd}
     * starts it with its output sent to a file. The script runs elevated, as vmrun's programs do.
     */
    public Spawn.Completed runPowerShell(Path vmx, String user, String password, String script, Duration timeout)
            throws IOException, InterruptedException {
        String name = GuestUnattend.GUEST_FOLDER + "\\script-" + HexFormat.of().formatHex(randomBytes());
        Path hostScript = Files.createTempFile("botmaker-guest", ".ps1");
        Path hostLog = Files.createTempFile("botmaker-guest", ".log");
        try {
            // UTF-8 with its mark, or Windows PowerShell reads the file in the guest's ANSI code page.
            Files.writeString(hostScript, "\uFEFF[Console]::OutputEncoding = [Text.Encoding]::UTF8\n" + script,
                    StandardCharsets.UTF_8);
            Commands.require(copyToGuest(vmx, user, password, hostScript, name + ".ps1"),
                    "VMware couldn't put a script in the VM");
            Spawn.Completed done = Commands.run(timeout, powerShellCommand(vmx, user, password, name));
            Spawn.Completed ran = guestExitCode(done);
            if (!done.ok() && ran == done) return done;
            String output = copyFromGuest(vmx, user, password, name + ".log", hostLog).ok()
                    ? Files.readString(hostLog, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "") : "";
            return new Spawn.Completed(ran.exitCode(), output);
        } finally {
            for (String extension : List.of(".ps1", ".log")) deleteInGuest(vmx, user, password, name + extension);
            Files.deleteIfExists(hostScript);
            Files.deleteIfExists(hostLog);
        }
    }

    List<String> powerShellCommand(Path vmx, String user, String password, String name) {
        return guestCommand(vmx, user, password, "runProgramInGuest", "C:\\Windows\\System32\\cmd.exe",
                "/c C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe -NoProfile -ExecutionPolicy Bypass"
                        + " -File " + name + ".ps1 > " + name + ".log 2>&1");
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[6];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static final java.util.regex.Pattern GUEST_EXIT =
            java.util.regex.Pattern.compile("non-zero exit code:\\s*(-?\\d+)");

    /** {@code done} with the guest program's exit code where vmrun reported one. */
    static Spawn.Completed guestExitCode(Spawn.Completed done) {
        java.util.regex.Matcher m = GUEST_EXIT.matcher(done.output());
        return m.find() ? new Spawn.Completed(Integer.parseInt(m.group(1)), done.output()) : done;
    }

    /** Copies this PC's {@code file} to {@code guestPath}, replacing what is there; needs VMware Tools running. */
    public Spawn.Completed copyToGuest(Path vmx, String user, String password, Path file, String guestPath)
            throws IOException, InterruptedException {
        return Commands.run(SLOW, guestCommand(vmx, user, password, "copyFileFromHostToGuest", file.toString(),
                guestPath));
    }

    /** Copies the guest's {@code guestPath} to this PC's {@code file}; needs VMware Tools running. */
    public Spawn.Completed copyFromGuest(Path vmx, String user, String password, String guestPath, Path file)
            throws IOException, InterruptedException {
        return Commands.run(SLOW, guestCommand(vmx, user, password, "copyFileFromGuestToHost", guestPath,
                file.toString()));
    }

    /**
     * Shares this PC's {@code folder} with the running guest, read-only, as {@code name}: the guest reads it at
     * {@link #sharedFolderInGuest}. A share of that name left from before is replaced. vmrun adds a share
     * writable and makes it read-only in a second call: when any step fails, the share is taken away again.
     *
     * @return whether the VM's shared folders were on already, which {@link #unshareFolder} then leaves them
     */
    public boolean shareFolder(Path vmx, String name, Path folder) throws IOException, InterruptedException {
        boolean wereOn = "FALSE".equalsIgnoreCase(VmxFile.read(vmx).get("isolation.tools.hgfs.disable"));
        try {
            Commands.require(Commands.run(QUICK, vmrun().toString(), "-T", "ws", "enableSharedFolders",
                    vmx.toString()), "VMware couldn't turn the VM's shared folders on");
            Commands.run(QUICK, vmrun().toString(), "-T", "ws", "removeSharedFolder", vmx.toString(), name);
            Commands.require(Commands.run(QUICK, vmrun().toString(), "-T", "ws", "addSharedFolder", vmx.toString(),
                    name, folder.toString()), "VMware couldn't share " + folder + " with the VM");
            Commands.require(Commands.run(QUICK, vmrun().toString(), "-T", "ws", "setSharedFolderState",
                    vmx.toString(), name, folder.toString(), "readonly"),
                    "VMware couldn't make the VM's share of " + folder + " read-only");
            return wereOn;
        } catch (IOException | RuntimeException | InterruptedException e) {
            unshareFolder(vmx, name, wereOn);
            throw e;
        }
    }

    /**
     * Stops sharing {@code name}, and turns the VM's shared folders off unless {@code keepOn}; never throws on a
     * share already gone. VMware leaves the share's lines in the {@code .vmx}, past its {@code
     * sharedFolder.maxNum}, where they do nothing.
     */
    public void unshareFolder(Path vmx, String name, boolean keepOn) throws IOException, InterruptedException {
        Commands.run(QUICK, vmrun().toString(), "-T", "ws", "removeSharedFolder", vmx.toString(), name);
        if (!keepOn) Commands.run(QUICK, vmrun().toString(), "-T", "ws", "disableSharedFolders", vmx.toString());
    }

    /** Deletes the guest's file {@code guestPath}, if it is there; needs VMware Tools running. */
    public Spawn.Completed deleteInGuest(Path vmx, String user, String password, String guestPath)
            throws IOException, InterruptedException {
        return Commands.run(QUICK, guestCommand(vmx, user, password, "deleteFileInGuest", guestPath));
    }

    /** Where the guest reads the folder shared as {@code name}. */
    public static String sharedFolderInGuest(String name) {
        return "\\\\vmware-host\\Shared Folders\\" + name;
    }

    private List<String> guestCommand(Path vmx, String user, String password, String operation, String... arguments) {
        List<String> command = new ArrayList<>(List.of(vmrun().toString(), "-T", "ws", "-gu", user, "-gp", password,
                operation, vmx.toString()));
        command.addAll(List.of(arguments));
        return command;
    }

    /** Whether {@code path} exists in the guest; needs VMware Tools running there. */
    public boolean fileExistsInGuest(Path vmx, String user, String password, String path)
            throws IOException, InterruptedException {
        Spawn.Completed found = Commands.run(QUICK, fileExistsCommand(vmx, user, password, path));
        return found.ok() && found.output().contains("The file exists");
    }

    List<String> fileExistsCommand(Path vmx, String user, String password, String path) {
        return guestCommand(vmx, user, password, "fileExistsInGuest", path);
    }

    /** Creates a growable disk of {@code gigabytes} at {@code vmdk}. */
    public Spawn.Completed createDisk(Path vmdk, int gigabytes) throws IOException, InterruptedException {
        return Commands.run(QUICK, createDiskCommand(vmdk, gigabytes));
    }

    List<String> startCommand(Path vmx) {
        return List.of(vmrun().toString(), "-T", "ws", "start", vmx.toString(), "nogui");
    }

    List<String> stopCommand(Path vmx, boolean hard) {
        return List.of(vmrun().toString(), "-T", "ws", "stop", vmx.toString(), hard ? "hard" : "soft");
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
