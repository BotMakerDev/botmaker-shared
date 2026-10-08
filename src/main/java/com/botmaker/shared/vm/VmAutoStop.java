package com.botmaker.shared.vm;

import com.botmaker.shared.Spawn;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

/**
 * When BotMaker shuts a game VM down without being asked: when Studio closes, and after {@link #idleMinutes}
 * with nothing connected to its screen. Kept as {@value #FILE} in the VM's folder, a fact of this computer
 * whichever bot uses the VM. Both are off by default: the VM then runs until its Windows shuts down or someone
 * presses Shut down, so the next run doesn't wait for it to start.
 *
 * <p>Studio closing is watched from outside it ({@link #watchStudio}): a small PowerShell process waits for
 * Studio's process to end, then shuts the VM down as {@link VmSetup#shutDown} would. That holds when Studio
 * crashes too, and needs nothing of Studio's after it has gone.
 *
 * @param idleMinutes {@code 0} for never
 */
public record VmAutoStop(boolean withStudio, int idleMinutes) {

    public static final VmAutoStop OFF = new VmAutoStop(false, 0);
    static final String FILE = "power.properties";
    /** The process watching for Studio's end, so a second Studio window or a rebind doesn't start another. */
    static final String WATCH_FILE = "studio-watch.properties";

    public VmAutoStop {
        if (idleMinutes < 0) throw new IllegalArgumentException("idleMinutes: " + idleMinutes);
    }

    /** {@code vm}'s settings; {@link #OFF} when it has none or they don't read. Never throws. */
    public static VmAutoStop load(VmRecord vm) {
        Path file = vm.folder().resolve(FILE);
        if (!Files.isRegularFile(file)) return OFF;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
            return new VmAutoStop(Boolean.parseBoolean(p.getProperty("withStudio")),
                    Math.max(0, Integer.parseInt(p.getProperty("idleMinutes", "0"))));
        } catch (IOException | RuntimeException e) {
            return OFF;
        }
    }

    public void save(VmRecord vm) throws IOException {
        Properties p = new Properties();
        p.setProperty("withStudio", Boolean.toString(withStudio));
        p.setProperty("idleMinutes", Integer.toString(idleMinutes));
        try (Writer w = Files.newBufferedWriter(vm.folder().resolve(FILE), StandardCharsets.UTF_8)) {
            p.store(w, "When BotMaker shuts this VM down by itself");
        }
    }

    /**
     * Whether the running {@code vm} has nothing connected to its screen: no bot's session, no VM screen in
     * Studio. QEMU only, which counts its VNC clients; a VMware VM is never idle, as Workstation doesn't say.
     */
    public static boolean unwatched(VmRecord vm) {
        if (vm.hypervisor() != Hypervisor.QEMU) return false;
        try (QmpClient qmp = QmpClient.connect(vm.qmpPort())) {
            return qmp.vncClients() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Makes sure a watcher will shut the running {@code vm} down once the process {@code studio} ends. Nothing
     * happens while a watcher runs for the VM's current ports, whichever Studio it watches: with two open, the
     * first to close shuts the VM down, rather than each replacing the other's watcher every minute.
     */
    public static void watchStudio(VmRecord vm, long studio) throws IOException {
        String script = watchScript(vm, studio).orElseThrow(() -> new IOException("This VM's hypervisor is unknown."));
        Path file = vm.folder().resolve(WATCH_FILE);
        Properties p = readWatch(file);
        if (Integer.toString(vm.qmpPort()).equals(p.getProperty("port")) && watcher(p.getProperty("pid")).isPresent()) {
            return;
        }
        stopWatching(vm);
        Process watcher = Spawn.detached("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
        Properties next = new Properties();
        next.setProperty("pid", Long.toString(watcher.pid()));
        next.setProperty("studio", Long.toString(studio));
        next.setProperty("port", Integer.toString(vm.qmpPort()));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            next.store(w, "The process that shuts this VM down when Studio closes");
        }
    }

    /** Ends {@code vm}'s Studio watcher, if one runs: the setting was turned off, or the VM stopped. */
    public static void stopWatching(VmRecord vm) {
        Path file = vm.folder().resolve(WATCH_FILE);
        Properties p = readWatch(file);
        watcher(p.getProperty("pid")).ifPresent(ProcessHandle::destroy);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // an old file names a process that has gone; the next watch replaces it
        }
    }

    /**
     * The watcher: waits for {@code studio} to end, then presses the VM's power button, and powers it off when
     * Windows hasn't finished three minutes later. Single-quoted throughout: it travels as an encoded command,
     * but stays readable in a process list's decoding.
     */
    static Optional<String> watchScript(VmRecord vm, long studio) {
        String wait = "Wait-Process -Id " + studio + " -ErrorAction SilentlyContinue\n";
        return switch (vm.hypervisor()) {
            case QEMU -> Optional.of(wait
                    + "function Qmp($command) {\n"
                    + "  $c = New-Object Net.Sockets.TcpClient('127.0.0.1', " + vm.qmpPort() + ")\n"
                    + "  $s = $c.GetStream(); $r = New-Object IO.StreamReader($s)\n"
                    + "  $w = New-Object IO.StreamWriter($s); $w.AutoFlush = $true\n"
                    + "  $null = $r.ReadLine(); $w.WriteLine('{\"execute\":\"qmp_capabilities\"}'); $null = $r.ReadLine()\n"
                    + "  $w.WriteLine('{\"execute\":\"' + $command + '\"}'); $null = $r.ReadLine(); $c.Close()\n"
                    + "}\n"
                    + "try { Qmp 'system_powerdown' } catch { exit }\n"
                    + "$until = (Get-Date).AddMinutes(3)\n"
                    + "while ((Get-Date) -lt $until) {\n"
                    + "  Start-Sleep -Seconds 2\n"
                    + "  try { (New-Object Net.Sockets.TcpClient('127.0.0.1', " + vm.qmpPort() + ")).Close() } catch { exit }\n"
                    + "}\n"
                    + "try { Qmp 'quit' } catch { }\n");
            case VMWARE -> VmwareWorkstation.find().map(ws -> {
                String vmrun = "& '" + ws.vmrun().toString().replace("'", "''") + "' -T ws stop '"
                        + vm.vmx().toString().replace("'", "''") + "'";
                return wait + vmrun + " soft\nif ($LASTEXITCODE -ne 0) { " + vmrun + " hard }\n";
            });
            case UNKNOWN -> Optional.empty();
        };
    }

    private static Properties readWatch(Path file) {
        Properties p = new Properties();
        if (!Files.isRegularFile(file)) return p;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            // read as no watcher
        }
        return p;
    }

    /**
     * The watcher {@code pid} names, while it runs. A pid outlives its process and may be given to another
     * program, so only a PowerShell counts.
     */
    private static Optional<ProcessHandle> watcher(String pid) {
        try {
            return pid == null ? Optional.empty() : ProcessHandle.of(Long.parseLong(pid))
                    .filter(ProcessHandle::isAlive)
                    .filter(h -> h.info().command()
                            .map(c -> c.toLowerCase(java.util.Locale.ROOT).endsWith("powershell.exe")).orElse(false));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
