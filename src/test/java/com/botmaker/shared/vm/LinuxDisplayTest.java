package com.botmaker.shared.vm;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Linux game VM's displays: what the guest runs for each. Live: session's {@code LinuxDisplaysLiveTest}. */
class LinuxDisplayTest {

    @Test
    void aNewDisplayTakesTheLowestFreeNumberOneAtATimeWithItsOwnPassword() {
        String made = LinuxDisplay.newScript();
        assertTrue(made.contains("flock 9"), "two bots opening displays at once would pick the same number");
        assertTrue(made.contains("while systemctl is-active -q botmaker-display-$n || [ -e /tmp/.X11-unix/X$n ]"), made);
        assertTrue(made.contains("vncpasswd -f < /run/botmaker/new-$1.pass > \"$d/auth\""), made);
        assertTrue(made.contains("trap 'rm -f /run/botmaker/new-$1.pass' EXIT"), "the password's plain copy goes");
        assertTrue(made.contains("systemctl stop botmaker-display-$n || true; exit 1"), "a display that failed ends");
        assertTrue(made.contains("systemd-run --quiet --uid=botmaker --unit=botmaker-display-$n"), made);
        assertTrue(made.endsWith("echo $n\n"), made);
    }

    @Test
    void theDisplayIsXvncWithItsPasswordThatEndsByItselfOnceNobodyWatches() {
        String display = LinuxDisplay.displayScript();
        assertTrue(display.contains("Xvnc :$n -geometry ${2}x$3 -depth 24 -rfbport $((5900 + n)) -rfbauth \"$d/auth\""),
                display);
        assertTrue(display.contains("-SecurityTypes VncAuth -MaxDisconnectionTime 60"), display);
        assertTrue(display.contains("DISPLAY=:$n openbox &"), display);
        assertTrue(display.contains(LinuxDisplay.LIST_SCRIPT + " $n &"), display);
    }

    @Test
    void theListLoopWritesWindowsAndTheGameInTheColumnsTheHostReads() {
        String list = LinuxDisplay.listScript();
        assertTrue(list.contains("xwininfo -id \"$w\""), "xdotool's position is off by the frame, live");
        assertTrue(list.contains("_NET_FRAME_EXTENTS"), list);
        assertTrue(list.contains("printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n'"), list);
        assertTrue(list.contains("mv \"$d/windows.part\" \"$d/windows.tsv\""), "the host never reads half a list");
        assertTrue(list.contains("/sys/fs/cgroup/system.slice/botmaker-game-$n.service/cgroup.procs"), list);
    }

    @Test
    void aLaunchEndsTheLastOneAndAStopSaysWhatOutlivedIt() {
        String launch = LinuxDisplay.launchCommand(3);
        assertTrue(launch.startsWith("systemctl stop botmaker-game-3 2>/dev/null; "), launch);
        assertTrue(launch.contains("--unit=botmaker-game-3 -p RemainAfterExit=yes --setenv=DISPLAY=:3"), launch);
        assertTrue(launch.endsWith("/bin/sh /run/botmaker/display-3/launch.sh"), launch);
        String stop = LinuxDisplay.stopCommand(3);
        assertTrue(stop.contains("systemctl stop botmaker-game-3; "), stop);
        assertTrue(stop.contains("printf '" + GuestGame.SURVIVED + "\\t%s\\t%s\\n'"), stop);
    }

    @Test
    void onlyALinuxVmOnQemuHasDisplays() {
        VmRecord windows = new VmRecord(Path.of("vm"), "g", Hypervisor.QEMU, VmRecord.Stage.READY,
                new VmSize(2, 4096, 64), Path.of("w.iso"), "en-US", 5900, 40001, 40002, 40003, GuestOs.WINDOWS);
        assertThrows(java.io.IOException.class, () -> LinuxDisplay.requireLinuxOnQemu(windows));
    }
}
