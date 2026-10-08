package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.vnc.Keysyms;
import com.botmaker.shared.vnc.VncController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One bot's screen in a Linux game VM: an X display of its own in the guest ({@code Xvnc :N} with Openbox), so
 * several bots share the VM, each with its own pointer, keyboard and windows, and the user's own cursor stays
 * free. Its game runs on it ({@link #launch}), and closing it ends both.
 *
 * <p>In the guest, as the VM's user, each display is two systemd units: {@code botmaker-display-N} (Xvnc,
 * Openbox, and a loop that lists its windows and its game's processes into files each second) and
 * {@code botmaker-game-N} (the game, whose processes are its control group, so a stop ends all of them). Xvnc
 * ends by itself a minute after its last viewer left ({@code -MaxDisconnectionTime}), so a bot that died without
 * closing leaves nothing running for long.
 *
 * <p>From here it is reached through a loopback port that QEMU forwards to the display's port in the guest, added
 * while the VM runs ({@code hostfwd_add}). It has a VNC password of its own, which goes into the guest as a file
 * (the guest agent logs the command lines it runs, never a file's contents). QEMU only: a Linux VM runs there.
 */
public final class LinuxDisplay implements AutoCloseable {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 800;
    static final String FOLDER = "/run/botmaker";
    static final String DISPLAY_SCRIPT = "/usr/local/sbin/botmaker-display";
    static final String NEW_SCRIPT = "/usr/local/sbin/botmaker-display-new";
    static final String LIST_SCRIPT = "/usr/local/sbin/botmaker-windows";
    /** QEMU's network card, as {@link Qemu#command} names it. */
    private static final String NETDEV = "net0";
    private static final int AGENT_TIMEOUT_MS = 30_000;
    private static final Duration GUEST_COMMAND = Duration.ofSeconds(60);
    private static final long FIRST_FRAME_MS = 30_000;

    private final VmRecord vm;
    private final int number;
    private final int hostPort;
    private final VncController screen;

    private LinuxDisplay(VmRecord vm, int number, int hostPort, VncController screen) {
        this.vm = vm;
        this.number = number;
        this.hostPort = hostPort;
        this.screen = screen;
    }

    /**
     * A new display in {@code vm}'s guest, which must have signed in ({@link VmSetup#guestReady}), and its screen
     * connected; {@code title} names the screen's window. Nothing is left in the guest when it fails.
     */
    public static LinuxDisplay open(VmRecord vm, String title) throws IOException, InterruptedException {
        requireLinuxOnQemu(vm);
        String password = VmCredentials.randomText(8);
        String token = VmCredentials.randomText(12);
        int number;
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
            install(agent);
            agent.writeFile(FOLDER + "/new-" + token + ".pass", (password + "\n").getBytes(StandardCharsets.UTF_8));
            GuestAgent.Ran made = agent.run(NEW_SCRIPT, List.of(token, Integer.toString(WIDTH), Integer.toString(HEIGHT)),
                    GUEST_COMMAND);
            String said = made.output().strip();
            if (made.exitCode() != 0 || !said.matches("\\d+")) {
                throw new IOException("The VM couldn't open a screen for the bot (exit code " + made.exitCode() + "): "
                        + said);
            }
            number = Integer.parseInt(said);
        }
        int hostPort = 0;
        try {
            hostPort = VmSetup.freePort(List.of(vm.vncPort(), vm.qmpPort(), vm.agentPort(), vm.eventsPort()));
            monitor(vm, "hostfwd_add " + NETDEV + " tcp:127.0.0.1:" + hostPort + "-:" + (5900 + number));
            VncController screen = VncController.connect("127.0.0.1", hostPort, password, Keysyms.NativeKeys.VIRTUAL_KEY,
                    title, new GuestWindows.Cached(() -> readText(vm, folder(number) + "/windows.tsv")), FIRST_FRAME_MS);
            return new LinuxDisplay(vm, number, hostPort, screen);
        } catch (IOException | RuntimeException e) {
            end(vm, number, hostPort);
            throw e;
        }
    }

    /** Which display this is in the guest: {@code :N}. */
    public int number() {
        return number;
    }

    /** The display's input and capture, its windows listed by the guest. */
    public VncController screen() {
        return screen;
    }

    /**
     * Starts {@code spec} on this display ({@link GuestLaunch#linuxCommand}), ending what the last launch here
     * started first, and returns at once.
     *
     * @throws IllegalArgumentException for a kind the guest can't start
     * @throws IOException              when the guest couldn't start it
     */
    public void launch(LaunchSpec spec) throws IOException, InterruptedException {
        String command = GuestLaunch.linuxCommand(spec).orElseThrow(() -> new IllegalArgumentException(
                "A Linux game VM can't start " + spec.describe() + "."));
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
            // A file, not a command line: the agent logs its command lines, and a file needs no quoting.
            agent.writeFile(folder(number) + "/launch.sh", ("#!/bin/sh\n"
                    + "export PATH=/usr/local/bin:/usr/bin:/bin:/usr/games\n"
                    + command + "\n").getBytes(StandardCharsets.UTF_8));
            GuestAgent.Ran ran = agent.run("/bin/sh", List.of("-c", launchCommand(number)), GUEST_COMMAND);
            if (ran.exitCode() != 0) {
                throw new IOException("The VM couldn't start " + spec.describe() + " (exit code " + ran.exitCode() + "): "
                        + ran.output().strip());
            }
        }
    }

    /**
     * The game's processes on this display, as the guest's loop listed them in the last second; with {@code stop},
     * asked afresh and ended, and the result names what was ended.
     *
     * @throws IOException when the guest couldn't be asked, or a process outlived the stop
     */
    public GuestGame.Found game(boolean stop) throws IOException, InterruptedException {
        if (!stop) {
            String listed = readText(vm, folder(number) + "/game.tsv");
            return listed == null ? GuestGame.Found.UNKNOWN : GuestGame.parse(listed);
        }
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), AGENT_TIMEOUT_MS)) {
            GuestAgent.Ran ran = agent.run("/bin/sh", List.of("-c", stopCommand(number)), GUEST_COMMAND);
            GuestGame.Found found = GuestGame.parse(ran.output());
            if (!found.survived().isEmpty()) {
                throw new IOException("The VM couldn't end " + String.join(", ", found.survived()) + ".");
            }
            return found;
        }
    }

    /** Whether its screen is still connected: it drops when Xvnc ends, or the VM does. */
    public boolean alive() {
        return screen.alive();
    }

    /** Disconnects, and ends the display and its game in the guest; quietly, as the VM may be gone. */
    @Override
    public void close() {
        screen.close();
        end(vm, number, hostPort);
    }

    private static void end(VmRecord vm, int number, int hostPort) {
        if (hostPort != 0) {
            try {
                monitor(vm, "hostfwd_remove " + NETDEV + " tcp:127.0.0.1:" + hostPort);
            } catch (IOException e) {
                // QEMU has ended, and its forwards with it
            }
        }
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), 5_000)) {
            agent.exec("/bin/systemctl", List.of("stop", "botmaker-game-" + number, "botmaker-display-" + number));
        } catch (IOException e) {
            // the guest is gone; Xvnc would end by itself anyway
        }
    }

    /** Throws unless {@code vm} is a Linux VM on QEMU, the one place a display is made. */
    static void requireLinuxOnQemu(VmRecord vm) throws IOException {
        if (vm.guestOs() != GuestOs.LINUX || vm.hypervisor() != Hypervisor.QEMU) {
            throw new IOException("The game VM " + vm.name() + " isn't a Linux VM on QEMU, which gives each bot a screen.");
        }
    }

    static String folder(int number) {
        return FOLDER + "/display-" + number;
    }

    /** Writes the guest's scripts, each time: a VM set up by an older BotMaker gets the current ones. */
    private static void install(GuestAgent agent) throws IOException, InterruptedException {
        GuestAgent.Ran ran = agent.run("/bin/sh", List.of("-c", "mkdir -p " + FOLDER + " && chmod 755 " + FOLDER),
                GUEST_COMMAND);
        if (ran.exitCode() != 0) throw new IOException("The VM couldn't make " + FOLDER + ": " + ran.output().strip());
        for (Map.Entry<String, String> script : Map.of(DISPLAY_SCRIPT, displayScript(), NEW_SCRIPT, newScript(),
                LIST_SCRIPT, listScript()).entrySet()) {
            agent.writeFile(script.getKey(), script.getValue().getBytes(StandardCharsets.UTF_8));
        }
        agent.run("/bin/chmod", List.of("755", DISPLAY_SCRIPT, NEW_SCRIPT, LIST_SCRIPT), GUEST_COMMAND);
    }

    /**
     * {@code botmaker-display-new TOKEN WIDTH HEIGHT}, as root: picks the lowest free display number, gives it the
     * password the host wrote as {@code new-TOKEN.pass}, starts its unit and waits for its X server; prints the
     * number. One at a time: two bots opening displays at once would pick the same number.
     */
    static String newScript() {
        return """
                #!/bin/sh
                set -e
                trap 'rm -f %1$s/new-$1.pass' EXIT
                exec 9>%1$s/displays.lock
                flock 9
                n=1
                while systemctl is-active -q botmaker-display-$n || [ -e /tmp/.X11-unix/X$n ]; do n=$((n + 1)); done
                d=%1$s/display-$n
                rm -rf "$d"
                mkdir -p "$d"
                vncpasswd -f < %1$s/new-$1.pass > "$d/auth"
                chown -R botmaker:botmaker "$d"
                chmod 600 "$d/auth"
                systemctl reset-failed botmaker-display-$n botmaker-game-$n 2>/dev/null || true
                systemd-run --quiet --uid=botmaker --unit=botmaker-display-$n --setenv=HOME=/home/botmaker \\
                    %2$s $n $2 $3
                i=0
                while [ ! -e /tmp/.X11-unix/X$n ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
                if [ ! -e /tmp/.X11-unix/X$n ]; then systemctl stop botmaker-display-$n || true; exit 1; fi
                echo $n
                """.formatted(FOLDER, DISPLAY_SCRIPT);
    }

    /**
     * {@code botmaker-display N WIDTH HEIGHT}, as the VM's user: Xvnc on port 5900+N with the display's password,
     * Openbox, and the list loop; it lasts as long as Xvnc does.
     */
    static String displayScript() {
        return """
                #!/bin/sh
                n=$1
                d=%1$s/display-$n
                Xvnc :$n -geometry ${2}x$3 -depth 24 -rfbport $((5900 + n)) -rfbauth "$d/auth" \\
                    -SecurityTypes VncAuth -MaxDisconnectionTime 60 -AlwaysShared -desktop "BotMaker $n" &
                x=$!
                i=0
                while [ ! -e /tmp/.X11-unix/X$n ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
                DISPLAY=:$n openbox &
                %2$s $n &
                wait $x
                """.formatted(FOLDER, LIST_SCRIPT);
    }

    /**
     * {@code botmaker-windows N}, each second: display N's windows into {@code windows.tsv}, in the Windows
     * guest's columns ({@link GuestWindows#parse}: id, pid, program, frame x, y, width, height, in front, title),
     * and its game's processes into {@code game.tsv} ({@link GuestGame#parse}). A window's frame is its client
     * area grown by the frame Openbox draws, as Windows lists a window's frame; the client's position is
     * {@code xwininfo}'s, as {@code xdotool getwindowgeometry} adds the frame's offset twice (live, Openbox). A
     * window with no process id (an old X client) is named by its class.
     */
    static String listScript() {
        return """
                #!/bin/sh
                n=$1
                d=%1$s/display-$n
                g=/sys/fs/cgroup/system.slice/botmaker-game-$n.service/cgroup.procs
                export DISPLAY=:$n
                while sleep 1; do
                  active=$(xdotool getactivewindow 2>/dev/null)
                  wmctrl -lp 2>/dev/null | while read -r id desk pid host title; do
                    [ "$desk" = "-1" ] && continue
                    w=$((id))
                    X=; Y=; WIDTH=; HEIGHT=
                    eval "$(xwininfo -id "$w" 2>/dev/null | sed -n \\
                        -e 's/^ *Absolute upper-left X: *\\(-*[0-9]*\\)$/X=\\1/p' \\
                        -e 's/^ *Absolute upper-left Y: *\\(-*[0-9]*\\)$/Y=\\1/p' \\
                        -e 's/^ *Width: *\\([0-9]*\\)$/WIDTH=\\1/p' -e 's/^ *Height: *\\([0-9]*\\)$/HEIGHT=\\1/p')"
                    [ -n "$X" ] && [ -n "$HEIGHT" ] || continue
                    set -- $(xprop -id "$w" _NET_FRAME_EXTENTS 2>/dev/null | sed -n 's/.*= *//p' | tr -d ',')
                    l=${1:-0}; r=${2:-0}; t=${3:-0}; b=${4:-0}
                    fg=0; [ "$w" = "$active" ] && fg=1
                    name=""
                    [ "$pid" -gt 0 ] 2>/dev/null && name=$(cat /proc/$pid/comm 2>/dev/null)
                    [ -n "$name" ] || name=$(xprop -id "$w" WM_CLASS 2>/dev/null | sed -n 's/.*", "\\(.*\\)"$/\\1/p')
                    title=$(printf '%%s' "$title" | tr '\\t' ' ')
                    printf '%%s\\t%%s\\t%%s\\t%%s\\t%%s\\t%%s\\t%%s\\t%%s\\t%%s\\n' "$w" "$pid" "$name" $((X - l)) $((Y - t)) \\
                        $((WIDTH + l + r)) $((HEIGHT + t + b)) "$fg" "$title"
                  done > "$d/windows.part" && mv "$d/windows.part" "$d/windows.tsv"
                  for p in $(cat "$g" 2>/dev/null); do
                    printf '%%s\\t%%s\\n' "$p" "$(cat /proc/$p/comm 2>/dev/null)"
                  done > "$d/game.part" && mv "$d/game.part" "$d/game.tsv"
                done
                """.formatted(FOLDER);
    }

    /**
     * Starts {@code launch.sh} as display N's game, as the VM's user, after ending the last one there. The unit
     * stays once its first program exits (a launcher that hands the game off), so its control group keeps every
     * process the launch started. A stop kills what is left 15 s after asking: Wine's services ignore the ask, and
     * systemd would otherwise wait 90 s; a Steam started here gets the 15 s to write its records. Each display has a Wine prefix of its own: one prefix has one
     * {@code wineserver}, which would be the first game's, and stopping that game would end the others'.
     */
    static String launchCommand(int number) {
        String d = folder(number);
        return "systemctl stop botmaker-game-" + number + " 2>/dev/null; "
                + "systemctl reset-failed botmaker-game-" + number + " 2>/dev/null; "
                + "chown botmaker:botmaker " + d + "/launch.sh && "
                + "systemd-run --quiet --uid=botmaker --unit=botmaker-game-" + number + " -p RemainAfterExit=yes "
                + "-p TimeoutStopSec=15 "
                + "--setenv=DISPLAY=:" + number + " --setenv=HOME=/home/botmaker "
                + "--setenv=WINEPREFIX=/home/botmaker/.wine-display-" + number + " --working-directory=/home/botmaker "
                + "/bin/sh " + d + "/launch.sh";
    }

    /**
     * Lists display N's game processes ({@link GuestGame#parse}), ends its unit, and lists, after
     * {@value GuestGame#SURVIVED}, any still running.
     */
    static String stopCommand(int number) {
        String procs = "/sys/fs/cgroup/system.slice/botmaker-game-" + number + ".service/cgroup.procs";
        return "for p in $(cat " + procs + " 2>/dev/null); do printf '%s\\t%s\\n' $p \"$(cat /proc/$p/comm)\"; done; "
                + "systemctl stop botmaker-game-" + number + "; "
                + "for p in $(cat " + procs + " 2>/dev/null); do printf '" + GuestGame.SURVIVED
                + "\\t%s\\t%s\\n' $p \"$(cat /proc/$p/comm)\"; done; true";
    }

    /** A guest file's text, or {@code null} when it can't be read. */
    private static String readText(VmRecord vm, String path) {
        try (GuestAgent agent = GuestAgent.connect(vm.agentPort(), 3_000)) {
            return agent.readFile(path).map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Runs a QEMU monitor command, as {@code hostfwd_add} is one only there. */
    private static void monitor(VmRecord vm, String command) throws IOException {
        try (QmpClient qmp = QmpClient.connect(vm.qmpPort())) {
            String said = qmp.execute("human-monitor-command", Map.of("command-line", command)).asText("");
            if (!said.isBlank()) throw new IOException("QEMU refused \"" + command + "\": " + said.strip());
        }
    }

    @Override
    public String toString() {
        return "LinuxDisplay[" + vm.name() + " :" + number + (hostPort != 0 ? " on " + hostPort : "") + "]";
    }
}
