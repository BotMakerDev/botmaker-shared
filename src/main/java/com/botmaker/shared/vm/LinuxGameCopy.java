package com.botmaker.shared.vm;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link GameCopy} into a Linux game VM on QEMU or Tart. The guest fetches the folder from the same
 * {@link FolderServer} with {@code curl}, as root, and gives it to the VM's user: QEMU's guest reaches it at
 * {@value #QEMU_HOST}, which QEMU hands to this PC's loopback; a Mac's, at the Mac's own address on Tart's NAT
 * network ({@link Tart#hostSide}), where it is served for the copy.
 * <ul>
 *   <li><b>Steam:</b> into its library ({@link #steamRoot}), with this PC's {@code .acf} beside it; Steam checks the
 *       files when it next starts. A Windows game runs there through Steam Play (Proton).</li>
 *   <li><b>Epic</b> (QEMU only: on a Mac the guest is arm64, and Legendary and Wine are x86 programs): into
 *       {@value #EPIC_GAMES}, then {@code legendary import}, which needs the VM's Legendary signed in
 *       ({@link Legendary}); before that the files wait there, and a copy made again imports them. A Windows game
 *       runs through Wine, installed in the VM by the first Epic copy.</li>
 * </ul>
 */
final class LinuxGameCopy {

    static final String HOME = "/home/" + VmSetup.GUEST_USER;
    static final String EPIC_GAMES = HOME + "/Games";
    /** Where a QEMU guest reaches this PC: QEMU's user network hands it to the loopback. */
    static final String QEMU_HOST = "10.0.2.2";
    /**
     * Wine's packages, 32-bit too: Legendary runs a Windows game with {@code wine}. Tried three times, the list
     * read afresh each time: live, a first try failed on archives the mirror had just replaced.
     */
    static final String WINE = "command -v wine >/dev/null || { for try in 1 2 3; do apt-get update -q; "
            + "DEBIAN_FRONTEND=noninteractive apt-get install -y -q -o DPkg::Lock::Timeout=600 "
            + "wine wine64 wine32:i386 && break; sleep 10; done; command -v wine >/dev/null; }";
    private static final Duration WINE_INSTALL = Duration.ofMinutes(30);

    private LinuxGameCopy() {}

    /**
     * Where Steam sets itself up at its first start in a guest on {@code hypervisor}: Ubuntu's package, under
     * {@code ~/.steam/steam}; on a Mac, Canonical's snap, under the snap's own folder of the user's.
     */
    static String steamRoot(Hypervisor hypervisor) {
        return hypervisor == Hypervisor.TART ? HOME + "/snap/steam/common/.local/share/Steam" : HOME + "/.steam/steam";
    }

    static String steamLibrary(Hypervisor hypervisor) {
        return steamRoot(hypervisor) + "/steamapps";
    }

    static GameCopy.Outcome copy(VmRecord vm, GameCopy.Source game, Consumer<String> progress)
            throws IOException, InterruptedException {
        Hypervisor hypervisor = vm.hypervisor();
        if (hypervisor != Hypervisor.QEMU && hypervisor != Hypervisor.TART) {
            throw new IOException("A game is copied into a Linux VM on QEMU or Tart only.");
        }
        boolean epic = game.launcher() == GuestLauncher.EPIC;
        if (epic) Legendary.requireQemu(vm);
        try (GuestShell guest = GuestShell.open(vm, GameCopy.AGENT_TIMEOUT_MS)) {
            if (!epic && !guest.fileExists(steamRoot(hypervisor) + "/steam.sh")) {
                throw new IOException("Steam hasn't set itself up in the VM yet: start it on this screen once first.");
            }
            if (epic && !guest.fileExists(GuestLauncher.EPIC.linuxExecutable(hypervisor))) {
                throw new IOException("Legendary isn't installed in the VM.");
            }
        }
        if (epic) {
            progress.accept("Installing Wine in the VM, once: Epic's games are Windows games…");
            GuestAgent.Ran wine;
            try (GuestShell guest = GuestShell.open(vm, GameCopy.COPY_REPLY_MS)) {
                wine = guest.run("/bin/sh", List.of("-c", WINE), WINE_INSTALL);
            }
            if (wine.exitCode() != 0) {
                throw new IOException("Wine didn't install in the VM: " + lastLine(wine.output()));
            }
        }
        String into = (epic ? EPIC_GAMES : steamLibrary(hypervisor) + "/common") + "/" + game.folder().getFileName();
        InetAddress serveOn = InetAddress.getLoopbackAddress();
        String host = QEMU_HOST;
        if (hypervisor == Hypervisor.TART) {
            InetAddress guestIp = Tart.required().ip(vm.name());
            serveOn = Tart.hostSide(guestIp).orElseThrow(() -> new IOException("This Mac has no address on the VM's "
                    + "network (" + guestIp.getHostAddress() + ")."));
            host = serveOn.getHostAddress();
        }
        GuestAgent.Ran ran;
        try (FolderServer server = FolderServer.serve(game.folder(), serveOn)) {
            Thread reporter = GameCopy.reporter(game, server.total(), server::sent, progress);
            try (GuestShell guest = GuestShell.open(vm, GameCopy.COPY_REPLY_MS)) {
                // A file, not a command line: the agent logs its command lines, and the server's token is in it.
                // One of its own: two copies at once would rewrite each other's while sh reads it. /run is
                // emptied at each start, and no display may have made the folder since.
                String script = LinuxDisplay.FOLDER + "/copy-" + server.token() + ".sh";
                guest.run("/bin/mkdir", List.of("-p", LinuxDisplay.FOLDER), Duration.ofSeconds(30));
                guest.writeFile(script, copyScript(host, server.port(), server.token(), into)
                        .getBytes(StandardCharsets.UTF_8));
                try {
                    ran = guest.run("/bin/sh", List.of(script), GameCopy.COPY);
                } finally {
                    guest.start("/bin/rm", List.of("-f", script));
                }
            } finally {
                GameCopy.stop(reporter);
            }
        }
        if (ran.exitCode() == GameCopy.NO_ROOM) throw new IOException(ran.output().strip());
        if (ran.exitCode() != 0) {
            // On a Mac the guest connects in to Studio, which the Mac's firewall may refuse or ask about.
            throw new IOException("Copying " + game.name() + " into the VM failed (exit code " + ran.exitCode() + "): "
                    + lastLine(ran.output()) + (hypervisor == Hypervisor.TART ? " If the Mac's firewall is on, let "
                    + "BotMaker accept incoming connections (System Settings ▸ Network ▸ Firewall)." : ""));
        }
        progress.accept("Recording " + game.name() + " in the VM's " + game.launcher().displayName() + "…");
        return epic ? imported(vm, game, into) : recorded(vm, game);
    }

    /**
     * Steam's record: this PC's {@code .acf}, which Steam reads at its next start. A Steam running now is left
     * alone: the VM's bots share it, and their games with it.
     */
    private static GameCopy.Outcome recorded(VmRecord vm, GameCopy.Source game) throws IOException, InterruptedException {
        String acf = steamLibrary(vm.hypervisor()) + "/appmanifest_" + game.id() + ".acf";
        try (GuestShell guest = GuestShell.open(vm, GameCopy.AGENT_TIMEOUT_MS)) {
            guest.writeFile(acf, Files.readAllBytes(game.manifest()));
            guest.run("/bin/chown", List.of(VmSetup.GUEST_USER + ":" + VmSetup.GUEST_USER, acf), Duration.ofSeconds(30));
        }
        return GameCopy.Outcome.STEAM_PLAY;
    }

    /** Legendary's record, once it is signed in; {@code into} holds the game. */
    private static GameCopy.Outcome imported(VmRecord vm, GameCopy.Source game, String into)
            throws IOException, InterruptedException {
        if (!Legendary.signedIn(vm)) return GameCopy.Outcome.SIGN_IN_FIRST;
        GuestAgent.Ran ran;
        try (GuestShell guest = GuestShell.open(vm, GameCopy.COPY_REPLY_MS)) {
            ran = guest.run("/usr/sbin/runuser", Legendary.asUser("-y", "import", "--skip-dlcs", game.id(), into),
                    Duration.ofMinutes(5));
        }
        if (ran.exitCode() != 0) {
            throw new IOException("Legendary didn't take " + game.name() + ": " + lastLine(ran.output()));
        }
        return GameCopy.Outcome.IMPORTED;
    }

    /**
     * The guest's copy into {@code into}, as root, from this PC at {@code host}: lists the server's files, skips
     * those already there whole, refuses (exit {@value GameCopy#NO_ROOM}, one sentence) when the disk lacks the
     * room, then fetches the rest, each tried three times, and gives the folder to the VM's user. The list names
     * files with {@code \}.
     */
    static String copyScript(String host, int port, String token, String into) {
        String user = VmSetup.GUEST_USER;
        return """
                #!/bin/sh
                base='http://%6$s:%1$d/%2$s'
                dest=%3$s
                list=$(mktemp)
                trap 'rm -f "$list"' EXIT
                tab=$(printf '\\t')
                curl -fsS "$base/list" -o "$list" || { echo "The VM couldn't reach this PC's copy."; exit 1; }
                runuser -u %4$s -- mkdir -p "$dest" || exit 1
                need=0
                while IFS="$tab" read -r size rel; do
                  [ -n "$rel" ] || continue
                  to="$dest/$(printf '%%s' "$rel" | tr '\\\\' '/')"
                  [ "$(stat -c %%s "$to" 2>/dev/null)" = "$size" ] && continue
                  need=$((need + size))
                done < "$list"
                free=$(df -B1 --output=avail "$dest" | tail -1)
                if [ "$free" -lt $((need + 1073741824)) ]; then
                  awk -v f="$free" -v n="$need" 'BEGIN { printf "The VM has %%.1f GB free, and the game needs %%.1f GB more.\\n", f / 1073741824, n / 1073741824 }'
                  exit %5$d
                fi
                while IFS="$tab" read -r size rel; do
                  [ -n "$rel" ] || continue
                  to="$dest/$(printf '%%s' "$rel" | tr '\\\\' '/')"
                  [ "$(stat -c %%s "$to" 2>/dev/null)" = "$size" ] && continue
                  mkdir -p "$(dirname "$to")"
                  try=1
                  until curl -fsS -G --data-urlencode "p=$rel" "$base/file" -o "$to" < /dev/null; do
                    [ $try -ge 3 ] && { echo "$rel: not fetched"; exit 1; }
                    try=$((try + 1))
                  done
                done < "$list"
                chown -R %4$s:%4$s "$dest"
                exit 0
                """.formatted(port, token, GuestLaunch.shellQuoted(into), user, GameCopy.NO_ROOM, host);
    }

    private static String lastLine(String output) {
        String[] lines = output.strip().split("\\R");
        return lines[lines.length - 1];
    }
}
