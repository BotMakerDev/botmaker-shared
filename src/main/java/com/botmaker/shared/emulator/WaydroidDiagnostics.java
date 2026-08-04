package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only probes for the ways a Waydroid setup is broken, each paired with the fix — <b>as text</b>.
 *
 * <p><b>Nothing here runs a remedy.</b> Every fix below needs {@code sudo}, and two of them (an nftables NAT
 * rule, replacing Android's native bridge) reach outside anything BotMaker owns: firewall state the user may
 * have configured deliberately, and a system-wide Android image. A bot maker prompting for a root password to
 * silently rewrite the host's packet filter is not a trade worth making for a convenience, and a fix applied
 * without being read is a fix the user cannot undo. So the commands are strings, shown with the symptom that
 * justifies them, and the user runs them.
 *
 * <p>The one exception is {@link Issue#RESOLUTION_MISMATCH}, whose remedy is
 * {@link WaydroidResolution#apply()} — no root, entirely inside Waydroid's own configuration, and something
 * BotMaker set the expectation for in the first place.
 *
 * <p>Each probe is split into a pure function over the text it examines and a thin reader that fetches that
 * text, so the whole set is testable on a machine with no Waydroid at all.
 */
public final class WaydroidDiagnostics {

    /** Waydroid's system config; its {@code [properties]} block is where the native bridge is declared. */
    static final Path CONFIG = Path.of("/var/lib/waydroid/waydroid.cfg");

    /** The kernel flag that has to be on for the container's traffic to be routed out of the host. */
    static final Path IP_FORWARD = Path.of("/proc/sys/net/ipv4/ip_forward");

    /** The property an ARM-translation layer (libhoudini/libndk) installs itself under. */
    static final String NATIVE_BRIDGE_PROP = "ro.dalvik.vm.native.bridge";

    private WaydroidDiagnostics() {}

    /** What is wrong. Ordered by dependency: fixing an earlier one can resolve a later one. */
    public enum Issue {
        /** The LXC container isn't running, so nothing else can be. */
        CONTAINER_DOWN,
        /** The container is up but the Android session isn't. */
        SESSION_STOPPED,
        /** Android has no route to the internet — apps show "no connection" while the host is online. */
        NO_INTERNET,
        /** No ARM translation layer, so ARM-only apps refuse to install or crash on start. */
        NO_NATIVE_BRIDGE,
        /** Android's framebuffer size doesn't match the window it is hosted in, so tap coordinates are scaled. */
        RESOLUTION_MISMATCH
    }

    /**
     * One detected problem, everything the user needs to act on it, and nothing that acts on its own.
     *
     * @param issue    which problem this is
     * @param symptom  what the user sees, in their terms
     * @param remedy   what the fix does and why, one or two sentences
     * @param commands the commands to run, in order; empty when the fix is not a command
     * @param docUrl   upstream documentation, or {@code null}
     */
    public record Finding(Issue issue, String symptom, String remedy, List<String> commands, String docUrl) {

        public Finding {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }

        /** The commands as one copy-pasteable block — what a "Copy" button puts on the clipboard. */
        public String commandBlock() {
            return String.join("\n", commands);
        }

        /** Whether BotMaker can fix this itself; true only for the no-root, Waydroid-internal case. */
        public boolean selfFixable() {
            return issue == Issue.RESOLUTION_MISMATCH;
        }
    }

    /**
     * Every problem currently detectable, in dependency order. Empty means the probes found nothing wrong —
     * which is the answer that makes the panel worth showing, since it rules the setup out as the cause.
     *
     * @param expected the framebuffer size the caller intends to run at, or {@code null} to skip that check
     */
    public static List<Finding> run(WaydroidResolution expected) {
        List<Finding> findings = new ArrayList<>();
        if (!WaydroidCli.available()) {
            return findings;   // not installed is not "misconfigured"; the picker already says so
        }
        add(findings, containerDown(WaydroidCli.runAnyExit("systemctl", "is-active", "waydroid-container")));
        WaydroidStatus status = WaydroidStatus.read();
        add(findings, sessionStopped(status));
        add(findings, noInternet(readOrNull(IP_FORWARD), status, defaultInterface(WaydroidCli.run("ip", "route"))));
        add(findings, noNativeBridge(readOrNull(CONFIG)));
        add(findings, resolutionMismatch(WaydroidResolution.read(), expected));
        return List.copyOf(findings);
    }

    /** As {@link #run(WaydroidResolution)} without the resolution check. */
    public static List<Finding> run() {
        return run(null);
    }

    // --- the probes: pure over the text they examine ---

    /**
     * {@code systemctl is-active waydroid-container} prints {@code active} when it is up. Note it exits
     * <em>non-zero</em> when it is not, which is why the caller uses {@link WaydroidCli#runAnyExit} — the word
     * we need is on a failing command's stdout. A null reading (no systemd, no systemctl) is not a finding:
     * absence of evidence would otherwise be reported as a broken container on every non-systemd distro.
     */
    static Finding containerDown(String isActiveOutput) {
        if (isActiveOutput == null || isActiveOutput.isBlank() || isActiveOutput.trim().startsWith("active")) {
            return null;
        }
        return new Finding(Issue.CONTAINER_DOWN,
                "The waydroid-container service is not running (systemctl says \"" + isActiveOutput.trim()
                        + "\"), so nothing Android-side can start.",
                "Start the system service that hosts the Android container. If it fails immediately, "
                        + "`journalctl -u waydroid-container -e` says why.",
                List.of("sudo systemctl restart waydroid-container"),
                null);
    }

    /** The Android userspace is down. Benign and expected before a first launch — offered, not alarming. */
    static Finding sessionStopped(WaydroidStatus status) {
        if (status == null || status.sessionRunning()) {
            return null;
        }
        return new Finding(Issue.SESSION_STOPPED,
                "The Waydroid session is stopped, so there is no Android to connect to yet.",
                "Start the session. BotMaker also starts it as part of launching Waydroid, so this is only "
                        + "worth running by hand when you want the container up without the UI.",
                List.of("waydroid session start"),
                null);
    }

    /**
     * Android has no internet while the host does. The host-side cause is that the container's traffic is
     * neither forwarded nor NAT'd out of the default interface.
     *
     * <p>The remedy is the nftables sequence that was verified working on a live Fedora/KDE box — deliberately
     * <em>not</em> the upstream troubleshooting page, which did not fix it there. Note the {@code ufw} steps:
     * the masquerade rule has to be added with the firewall down and the firewall re-enabled afterwards.
     *
     * @param ipForward     the contents of {@code /proc/sys/net/ipv4/ip_forward}, or null if unreadable
     * @param status        the session status; the check is skipped while the session is down
     * @param defaultIface  the host's default-route interface, for the masquerade rule
     */
    static Finding noInternet(String ipForward, WaydroidStatus status, String defaultIface) {
        if (ipForward == null || "1".equals(ipForward.trim())) {
            return null;   // forwarding is on; if apps still have no network it is not this
        }
        String iface = defaultIface == null || defaultIface.isBlank() ? "<your-default-interface>" : defaultIface;
        return new Finding(Issue.NO_INTERNET,
                "IP forwarding is off on the host, so the container's traffic cannot leave it — Android apps "
                        + "report no connection even though this machine is online"
                        + (status != null && status.sessionRunning()
                        ? " (check with: waydroid shell ping -c1 google.com)." : "."),
                "Turn on forwarding and NAT the container's subnet out of the default interface. The firewall "
                        + "must be down while the rule is added and turned back on afterwards. Upstream's "
                        + "networking page did not fix this case; this sequence did.",
                List.of("sudo ufw disable",
                        "sudo nft add table ip nat",
                        "sudo nft add chain ip nat postrouting { type nat hook postrouting priority 100 \\; }",
                        "sudo nft add rule ip nat postrouting oifname \"" + iface + "\" masquerade",
                        "sudo sysctl -w net.ipv4.ip_forward=1",
                        "echo \"net.ipv4.ip_forward=1\" | sudo tee -a /etc/sysctl.conf",
                        "sudo sysctl -p",
                        "sudo systemctl restart waydroid-container",
                        "waydroid session stop",
                        "waydroid session start",
                        "sudo ufw enable"),
                "https://docs.waydro.id/debugging/networking-issues");
    }

    /**
     * No ARM translation layer. Waydroid's images are x86_64, so an app shipping only ARM native libraries —
     * which is most games — fails to install or crashes on launch, with nothing in the UI saying why.
     *
     * <p>Probed by reading the {@code [properties]} block of {@link #CONFIG}: {@code libhoudini} declares
     * itself there, and its absence is the whole condition.
     */
    static Finding noNativeBridge(String configText) {
        if (configText == null || configText.contains(NATIVE_BRIDGE_PROP)) {
            return null;
        }
        return new Finding(Issue.NO_NATIVE_BRIDGE,
                "No ARM translation layer is configured (" + NATIVE_BRIDGE_PROP + " is absent from "
                        + CONFIG + "), so ARM-only apps will refuse to install or crash at startup.",
                "Install libhoudini with the community waydroid_script tool. It rewrites the system image, so "
                        + "read what it does before running it; BotMaker will not run it for you.",
                List.of("git clone https://github.com/casualsnek/waydroid_script",
                        "cd waydroid_script",
                        "sudo python3 -m venv venv",
                        "source venv/bin/activate",
                        "sudo pip install -r requirements.txt",
                        "sudo python3 main.py install libhoudini"),
                "https://github.com/casualsnek/waydroid_script");
    }

    /**
     * Android's framebuffer is a different size from what the session will be hosted at, which puts a scaler
     * between the pixels a template was authored against and the pixels a tap lands on.
     *
     * @param actual   the configured size, or null when unset (Waydroid's own default — not a mismatch we can assert)
     * @param expected the size the caller intends to run at, or null to skip
     */
    static Finding resolutionMismatch(WaydroidResolution actual, WaydroidResolution expected) {
        if (expected == null || actual == null || actual.equals(expected)) {
            return null;
        }
        return new Finding(Issue.RESOLUTION_MISMATCH,
                "Android is configured for " + actual + " but the session will be hosted at " + expected
                        + ", so the image is scaled and tap coordinates will not line up with what is matched.",
                "Set Waydroid's display size to match. This is the one fix BotMaker can apply itself — it "
                        + "needs no root — but it restarts the Waydroid session.",
                List.of("waydroid prop set " + WaydroidResolution.WIDTH_PROP + " " + expected.width(),
                        "waydroid prop set " + WaydroidResolution.HEIGHT_PROP + " " + expected.height(),
                        "waydroid session stop",
                        "waydroid session start"),
                null);
    }

    /**
     * The interface the host's default route leaves by, parsed out of {@code ip route} — the {@code dev X}
     * of the {@code default via …} line. Null when there is no default route to read.
     */
    static String defaultInterface(String ipRouteOutput) {
        if (ipRouteOutput == null) {
            return null;
        }
        for (String line : ipRouteOutput.split("\\R")) {
            if (!line.startsWith("default ")) {
                continue;
            }
            String[] words = line.trim().split("\\s+");
            for (int i = 0; i + 1 < words.length; i++) {
                if ("dev".equals(words[i])) {
                    return words[i + 1];
                }
            }
        }
        return null;
    }

    // --- plumbing ---

    private static void add(List<Finding> findings, Finding finding) {
        if (finding != null) {
            findings.add(finding);
        }
    }

    /** File contents, or null when it doesn't exist or isn't readable (a probe never throws). */
    private static String readOrNull(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return null;
        }
    }
}
