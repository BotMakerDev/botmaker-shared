package com.botmaker.shared.vm;

import com.botmaker.shared.vnc.GuestWindow;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The windows on a Windows guest's desktop, which VNC can't tell: a hidden PowerShell loop in the signed-in
 * user's session lists them each second into {@value #LIST_FILE}, and the host reads that file through the guest
 * tools.
 *
 * <p>The loop is {@value #SCRIPT_FILE}, written and started by {@link #start} each time a bot or Studio connects
 * to the VM: a VM set up before guests listed their windows gets it the same way as a new one, and a guest
 * restart is followed by a connection that starts it again. A named mutex keeps one loop running however often
 * it is started.
 *
 * <p>Each line of the list is a visible, titled, uncloaked, not minimised top-level window other than the
 * desktop itself (the shell's {@code Program Manager}), topmost first:
 * handle, process id, process name, left, top, width, height, {@code 1} for the foreground window, title. The
 * rectangle is the window's visible frame (DWM's, without the invisible resize border), in the screen's pixels:
 * the loop runs DPI-aware. The file is written aside and swapped in, so a read never sees half of it.
 */
public final class GuestWindows {

    /** The loop's script in the guest. */
    public static final String SCRIPT_FILE = GuestUnattend.GUEST_FOLDER + "\\windows.ps1";
    /** The list the loop writes. */
    public static final String LIST_FILE = GuestUnattend.GUEST_FOLDER + "\\windows.tsv";
    /** How long a read list is used before it is read again: the loop writes one a second. */
    static final Duration FRESH = Duration.ofSeconds(1);
    /** How long a read through QEMU's guest agent may take. */
    private static final int AGENT_TIMEOUT_MS = 3_000;

    private GuestWindows() {}

    /** The loop's PowerShell. */
    static String script() {
        return """
                $ErrorActionPreference = 'Stop'
                $mutex = New-Object System.Threading.Mutex($false, 'Global\\BotMakerWindows')
                if (-not $mutex.WaitOne(0)) { exit 0 }
                Add-Type -TypeDefinition @'
                using System;
                using System.Collections.Generic;
                using System.Diagnostics;
                using System.Runtime.InteropServices;
                using System.Text;
                public static class BotMakerWindows {
                    delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
                    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
                    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
                    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
                    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
                    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
                    [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
                    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
                    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
                    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
                    [DllImport("user32.dll")] static extern IntPtr GetShellWindow();
                    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
                    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int size);
                    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int a, out int v, int size);
                    const int DWMWA_EXTENDED_FRAME_BOUNDS = 9, DWMWA_CLOAKED = 14;

                    static string Clean(string s) { return s.Replace('\\t', ' ').Replace('\\r', ' ').Replace('\\n', ' '); }

                    public static string List() {
                        StringBuilder text = new StringBuilder();
                        IntPtr front = GetForegroundWindow(), shell = GetShellWindow();
                        EnumWindows(delegate (IntPtr h, IntPtr l) {
                            if (h == shell || !IsWindowVisible(h) || IsIconic(h)) return true;
                            int length = GetWindowTextLength(h);
                            if (length == 0) return true;
                            int cloaked;
                            if (DwmGetWindowAttribute(h, DWMWA_CLOAKED, out cloaked, 4) == 0 && cloaked != 0) return true;
                            RECT r;
                            if (DwmGetWindowAttribute(h, DWMWA_EXTENDED_FRAME_BOUNDS, out r, 16) != 0 && !GetWindowRect(h, out r)) return true;
                            if (r.Right <= r.Left || r.Bottom <= r.Top) return true;
                            StringBuilder title = new StringBuilder(length + 1);
                            GetWindowText(h, title, title.Capacity);
                            uint pid;
                            GetWindowThreadProcessId(h, out pid);
                            string process = "";
                            try { process = Process.GetProcessById((int) pid).ProcessName; } catch (Exception) { }
                            text.Append(h.ToInt64()).Append('\\t').Append(pid).Append('\\t').Append(Clean(process))
                                .Append('\\t').Append(r.Left).Append('\\t').Append(r.Top)
                                .Append('\\t').Append(r.Right - r.Left).Append('\\t').Append(r.Bottom - r.Top)
                                .Append('\\t').Append(h == front ? "1" : "0").Append('\\t').Append(Clean(title.ToString()))
                                .Append('\\n');
                            return true;
                        }, IntPtr.Zero);
                        return text.ToString();
                    }
                }
                '@
                [BotMakerWindows]::SetProcessDPIAware() | Out-Null
                $utf8 = New-Object System.Text.UTF8Encoding($false)
                $aside = '%1$s.new'
                while ($true) {
                    try {
                        [System.IO.File]::WriteAllText($aside, [BotMakerWindows]::List(), $utf8)
                        if ([System.IO.File]::Exists('%1$s')) { [System.IO.File]::Replace($aside, '%1$s', [NullString]::Value) }
                        else { [System.IO.File]::Move($aside, '%1$s') }
                    } catch { }
                    Start-Sleep -Milliseconds 1000
                }
                """.formatted(LIST_FILE);
    }

    /** The command line that starts the loop on the guest's desktop, hidden, through {@link VmSetup#runOnDesktop}. */
    static String startCommand() {
        return "start \"\" /min C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe -NoProfile"
                + " -WindowStyle Hidden -ExecutionPolicy Bypass -File " + SCRIPT_FILE;
    }

    /**
     * Writes the loop into the running {@code vm}'s guest and starts it on the signed-in desktop; a loop already
     * running goes on, and this one ends at once. The guest must have signed in ({@link VmSetup#guestReady}).
     */
    public static void start(VmRecord vm, VmCredentials credentials) throws IOException, InterruptedException {
        VmSetup.requireWindows(vm);
        byte[] script = ("\uFEFF" + script()).getBytes(StandardCharsets.UTF_8);
        switch (vm.hypervisor()) {
            case QEMU -> {
                try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
                    agent.writeFile(SCRIPT_FILE, script);
                }
            }
            case VMWARE -> {
                VmwareWorkstation ws = VmwareWorkstation.find()
                        .orElseThrow(() -> new IOException("VMware Workstation isn't installed."));
                Path file = Files.createTempFile("botmaker-windows", ".ps1");
                try {
                    Files.write(file, script);
                    Commands.require(ws.copyToGuest(vm.vmx(), VmSetup.GUEST_USER, credentials.guest(), file, SCRIPT_FILE),
                            "VMware couldn't put the window list's script in the VM");
                } finally {
                    Files.deleteIfExists(file);
                }
            }
            case UNKNOWN -> throw new IOException("This VM's hypervisor is unknown.");
        }
        VmSetup.runOnDesktop(vm, credentials, startCommand());
    }

    /**
     * The guest's windows as the loop last listed them, read again in the background once a list is older than
     * {@link #FRESH} ({@link Cached}): what a {@code VncController} is given. Empty while the guest lists nothing
     * (the loop not started yet, the guest tools not answering); a read never throws.
     */
    public static Supplier<List<GuestWindow>> reader(VmRecord vm, VmCredentials credentials) {
        return new Cached(() -> read(vm, credentials));
    }

    /** The list file's text in {@code vm}'s guest, or {@code null} when it can't be read. */
    private static String read(VmRecord vm, VmCredentials credentials) {
        try {
            return switch (vm.hypervisor()) {
                case QEMU -> {
                    try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
                        yield agent.readFile(LIST_FILE).map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
                    }
                }
                case VMWARE -> {
                    VmwareWorkstation ws = VmwareWorkstation.find().orElse(null);
                    if (ws == null) yield null;
                    Path file = Files.createTempFile("botmaker-windows", ".tsv");
                    try {
                        yield ws.copyFromGuest(vm.vmx(), VmSetup.GUEST_USER, credentials.guest(), LIST_FILE, file).ok()
                                ? Files.readString(file, StandardCharsets.UTF_8) : null;
                    } finally {
                        Files.deleteIfExists(file);
                    }
                }
                case UNKNOWN -> null;
            };
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The windows in the loop's {@code text}; a line that doesn't read is skipped. */
    static List<GuestWindow> parse(String text) {
        List<GuestWindow> windows = new ArrayList<>();
        if (text == null) return windows;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", 9);
            if (f.length < 9) continue;
            try {
                windows.add(new GuestWindow(Long.parseLong(f[0].strip()), f[8].strip(), f[2].strip(),
                        new Rectangle(Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]),
                                Integer.parseInt(f[6])), "1".equals(f[7])));
            } catch (NumberFormatException e) {
                // a line from a newer loop, or a broken one
            }
        }
        return List.copyOf(windows);
    }

    /** Reads the lists in the background, so a bot's capture never waits on vmrun. */
    private static final Executor REFRESHER = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "guest-windows");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The list as last read. The first call reads it; after that, a call that finds it older than {@link #FRESH}
     * has it read again in the background and answers with what it has, so a caller never waits on the guest. A
     * read that fails keeps the last list, one slow guest call shouldn't make every window vanish, until reads
     * have failed for {@code gone}: a guest that restarted, or whose loop ended, lists nothing.
     */
    static final class Cached implements Supplier<List<GuestWindow>> {

        /** How long reads may fail before the list is dropped. */
        static final Duration GONE = Duration.ofSeconds(10);

        private final Supplier<String> source;
        private final Executor refresher;
        private final Duration gone;
        private final AtomicBoolean reading = new AtomicBoolean();
        private volatile List<GuestWindow> last = List.of();
        private volatile long readAt;
        private volatile long goodAt = System.nanoTime();
        private volatile boolean read;

        Cached(Supplier<String> source) {
            this(source, REFRESHER, GONE);
        }

        Cached(Supplier<String> source, Executor refresher, Duration gone) {
            this.source = source;
            this.refresher = refresher;
            this.gone = gone;
        }

        @Override
        public List<GuestWindow> get() {
            if (!read) {
                synchronized (this) {
                    if (!read) {
                        refresh();
                        read = true;
                    }
                }
            } else if (System.nanoTime() - readAt >= FRESH.toNanos() && reading.compareAndSet(false, true)) {
                refresher.execute(() -> {
                    try {
                        refresh();
                    } finally {
                        reading.set(false);
                    }
                });
            }
            return last;
        }

        private void refresh() {
            readAt = System.nanoTime();
            String text = source.get();
            long now = System.nanoTime();
            if (text != null) {
                last = parse(text);
                goodAt = now;
            } else if (now - goodAt > gone.toNanos()) {
                last = List.of();
            }
        }
    }
}
