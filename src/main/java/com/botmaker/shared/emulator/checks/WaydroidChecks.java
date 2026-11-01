package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.AdbEndpoint;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.WaydroidCli;
import com.botmaker.shared.emulator.WaydroidResolution;
import com.botmaker.shared.emulator.WaydroidStatus;
import com.botmaker.shared.platform.Os;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks on the user's Waydroid. Each probe is a pure function over the text it examines, and a thin
 * reader ({@link #run}) fetches that text, so the whole set is testable on a machine with no Waydroid.
 *
 * <p>These replaced {@code WaydroidDiagnostics} (2026-11-01), whose rule was "nothing here runs a remedy". The
 * user's decision: a broken Waydroid is BotMaker's to find <em>and fix</em>, on the user's click, root asked
 * once through the desktop's password window. A probe only says whether its Fix can do it ({@code fixable});
 * when it can't, the row says what the user must do and gives the commands.
 */
public final class WaydroidChecks {

    /** Waydroid's system config: {@code [waydroid]} settings and the {@code [properties]} Android boots with. */
    public static final Path CONFIG = Path.of("/var/lib/waydroid/waydroid.cfg");

    /** Waydroid's own install guide: BotMaker does not install Waydroid (user, 2026-11-01). */
    public static final String INSTALL_PAGE = "https://docs.waydro.id/usage/install-on-desktops";

    /** The kernel flag that has to be on for the container's traffic to be routed out of the host. */
    static final Path IP_FORWARD = Path.of("/proc/sys/net/ipv4/ip_forward");

    private static final Path CPUINFO = Path.of("/proc/cpuinfo");

    /** systemd's line when it can't exec the service's program: the file is the group. */
    private static final Pattern EXEC_DENIED = Pattern.compile("Failed to execute (\\S+): Permission denied");

    private WaydroidChecks() {}

    /**
     * Every check on this machine's Waydroid, in order; a failure among installed, running and booted leaves
     * the rest {@link CheckState#AFTER_ABOVE}.
     *
     * @param instance the Waydroid instance when one was discovered, for adb; {@code null} asks Waydroid itself
     * @param expected the size the bot runs at, or {@code null} to skip that check
     */
    public static List<CheckResult> run(EmulatorInstance instance, WaydroidResolution expected) {
        List<CheckResult> rows = new ArrayList<>();
        String config = read(CONFIG);
        rows.add(installed(Os.current().isLinux() && WaydroidCli.onPath("waydroid"), config != null));
        WaydroidStatus status = rows.getLast().failed() ? null : WaydroidStatus.read();
        if (status != null) {
            rows.add(running(WaydroidCli.runAnyExit("systemctl", "is-active", "waydroid-container"),
                    WaydroidCli.runAnyExit("journalctl", "-u", "waydroid-container", "-n", "20", "-o", "cat",
                            "--no-pager"), status));
        }
        // adb, not `waydroid prop get`: a session BotMaker nests in its own display answers "stopped" to that.
        AdbEndpoint adb = instance != null ? instance.adb()
                : status == null ? null : new AdbEndpoint.Tcp(status.ipAddress(), WaydroidStatus.ADB_PORT);
        if (!rows.getLast().failed()) {
            rows.add(booted(instance != null ? EmulatorReadiness.isReady(instance)
                    : "1".equals(prop(adb, "sys.boot_completed"))));
        }
        if (!rows.getLast().failed()) {
            rows.add(internet(read(IP_FORWARD), WaydroidCli.run("ip", "route")));
            rows.add(googlePlay(hasPlay(config)));
            rows.add(translator(read(CPUINFO), config, prop(adb, "ro.build.version.release"))
                    .withUndo(Backups.newest().isPresent()));
            CheckResult size = resolution(WaydroidResolution.read(), expected);
            if (size != null) rows.add(size);
        }
        return after(rows);
    }

    /** {@code rows} followed by an {@link CheckState#AFTER_ABOVE} row for each check that did not run. */
    static List<CheckResult> after(List<CheckResult> rows) {
        List<CheckResult> all = new ArrayList<>(rows);
        if (rows.getLast().failed()) {
            for (EmulatorCheck later : List.of(EmulatorCheck.WAYDROID_RUNNING, EmulatorCheck.ADB_BOOTED,
                    EmulatorCheck.INTERNET, EmulatorCheck.GOOGLE_PLAY, EmulatorCheck.ARM_TRANSLATOR)) {
                if (later.ordinal() > rows.getLast().check().ordinal()) all.add(CheckResult.afterAbove(later));
            }
        }
        all.add(doNotDisturb());
        return List.copyOf(all);
    }

    // --- the probes: pure over the text they examine ---

    static CheckResult installed(boolean onPath, boolean configExists) {
        if (!onPath) {
            return CheckResult.byHand(EmulatorCheck.WAYDROID_INSTALLED, "Waydroid is not installed.",
                    "Install it with your distribution's package, as Waydroid's guide says, then run its first "
                            + "setup. BotMaker does not install Waydroid.", List.of(), INSTALL_PAGE);
        }
        if (!configExists) {
            return CheckResult.byHand(EmulatorCheck.WAYDROID_INSTALLED,
                    "Waydroid is installed but has never been set up (" + CONFIG + " is missing).",
                    "Run Waydroid's first setup, with the Google Play image if you want Google Play.",
                    List.of("sudo waydroid init -s GAPPS"), INSTALL_PAGE);
        }
        return CheckResult.ok(EmulatorCheck.WAYDROID_INSTALLED, "Waydroid is installed and set up.");
    }

    /**
     * {@code systemctl is-active waydroid-container} prints {@code active} when it is up; it exits non-zero when
     * it is not, so the caller keeps a failing command's output. A null reading (no systemd) is not a finding.
     */
    static CheckResult running(String isActiveOutput, String journal, WaydroidStatus status) {
        // Only systemd's own words for a stopped unit: an error in their place ("Failed to connect to bus") is no
        // reading at all, and not a finding.
        String word = isActiveOutput == null ? "" : isActiveOutput.trim();
        boolean containerUp = !(word.equals("inactive") || word.equals("failed") || word.equals("deactivating"));
        if (!containerUp) {
            Matcher denied = journal == null ? null : EXEC_DENIED.matcher(journal);
            if (denied != null && denied.find()) {
                // Measured 2026-10-19: a python3.14 labelled user_home_t (copied from a home folder) made systemd's
                // exec of the waydroid script fail under SELinux; a restart only fails again.
                return CheckResult.byHand(EmulatorCheck.WAYDROID_RUNNING,
                        "The waydroid-container service can't start: the system refuses to run " + denied.group(1)
                                + " (\"Permission denied\").",
                        "Usually a file with the wrong SELinux label, such as a Python copied in from a home "
                                + "folder: restore the system labels, then start the service.",
                        List.of("sudo restorecon -Rv /usr/bin /usr/lib/waydroid",
                                "sudo systemctl restart waydroid-container"), null);
            }
            return CheckResult.fixable(EmulatorCheck.WAYDROID_RUNNING,
                    "The waydroid-container service is not running (systemctl says \"" + isActiveOutput.trim()
                            + "\").",
                    "Fix starts the service (it asks for your password), then Waydroid's session.");
        }
        // An empty status is `waydroid status` not answering: no reading, not a stopped session.
        if (status != null && !status.fields().isEmpty() && !status.sessionRunning()) {
            return CheckResult.fixable(EmulatorCheck.WAYDROID_RUNNING,
                    "Waydroid's session is stopped, so there is no Android yet.",
                    "Fix starts Waydroid, as ▶ Launch game does.");
        }
        return CheckResult.ok(EmulatorCheck.WAYDROID_RUNNING, "The container and the session are running.");
    }

    static CheckResult booted(boolean ready) {
        return ready ? CheckResult.ok(EmulatorCheck.ADB_BOOTED, "Android has started and adb reaches it.")
                : CheckResult.fixable(EmulatorCheck.ADB_BOOTED,
                "Android is still starting, or adb can't reach it.",
                "Fix connects adb and waits for Android to finish starting.");
    }

    /**
     * Android has no internet while the host does: the container's traffic is neither forwarded nor NAT'd out
     * of the default interface. The fix is the nftables sequence verified on a live Fedora/KDE box — not the
     * upstream troubleshooting page, which did not fix it there; the firewall is down while the rule is added.
     *
     * @param ipForward    {@code /proc/sys/net/ipv4/ip_forward}, or null when unreadable (not a finding)
     * @param ipRoute      {@code ip route}, for the default interface
     */
    static CheckResult internet(String ipForward, String ipRoute) {
        if (ipForward == null || "1".equals(ipForward.trim())) {
            return CheckResult.ok(EmulatorCheck.INTERNET, "The host forwards Android's traffic.");
        }
        String iface = defaultInterface(ipRoute);
        List<String> commands = internetCommands(iface == null ? "<your-default-interface>" : iface);
        String reason = "IP forwarding is off on the host, so Android's traffic cannot leave it: apps report no "
                + "connection though this machine is online.";
        if (iface == null) {
            return CheckResult.byHand(EmulatorCheck.INTERNET, reason, "No default route to send it out of: "
                    + "connect this machine, or fill in the interface below.", commands,
                    "https://docs.waydro.id/debugging/networking-issues");
        }
        return new CheckResult(EmulatorCheck.INTERNET, CheckState.FAILED, reason, "Fix turns forwarding on and "
                + "routes Android's traffic out of " + iface + " (it asks for your password).", true, false,
                commands, "https://docs.waydro.id/debugging/networking-issues");
    }

    /** The commands that give Android internet through {@code iface}, to run by hand as root. */
    public static List<String> internetCommands(String iface) {
        List<String> commands = new ArrayList<>();
        commands.add("ufw disable || true");
        commands.addAll(routeCommands(iface));
        commands.add("ufw enable || true");
        return List.copyOf(commands);
    }

    /** {@link #internetCommands} without the firewall, which Fix puts back only if it was on. Safe to run twice. */
    static List<String> routeCommands(String iface) {
        String masquerade = "oifname \"" + iface + "\" masquerade";
        return List.of("nft add table ip nat",
                "nft add chain ip nat postrouting { type nat hook postrouting priority 100 \\; }",
                "nft list chain ip nat postrouting | grep -qF '" + masquerade + "' || "
                        + "nft add rule ip nat postrouting " + masquerade,
                "sysctl -w net.ipv4.ip_forward=1",
                "grep -qx 'net.ipv4.ip_forward=1' /etc/sysctl.conf || echo 'net.ipv4.ip_forward=1' >> /etc/sysctl.conf",
                "systemctl restart waydroid-container");
    }

    /**
     * Whether Waydroid's image has Google Play: its system OTA channel is the {@code GAPPS} one. Null when the
     * config does not say (an image installed by hand).
     */
    static Boolean hasPlay(String config) {
        String ota = setting(config, "waydroid", "system_ota");
        if (ota == null) return null;
        return ota.contains("GAPPS");
    }

    static CheckResult googlePlay(Boolean hasPlay) {
        if (hasPlay == null) return CheckResult.info(EmulatorCheck.GOOGLE_PLAY, "Can't tell whether this image has "
                + "Google Play.");
        return hasPlay ? CheckResult.ok(EmulatorCheck.GOOGLE_PLAY, "This image has Google Play.")
                : CheckResult.fixable(EmulatorCheck.GOOGLE_PLAY, "This image has no Google Play: games install "
                + "from files only.", "Fix re-initialises Waydroid with its Google Play image (it asks for your "
                + "password and replaces the system image), then opens Google's device registration.");
    }

    /**
     * The ARM translator against the host's processor.
     *
     * @param cpuinfo {@code /proc/cpuinfo}
     * @param config  {@link #CONFIG}: its {@code [properties]} name the translator, its {@code [waydroid]} say
     *                whether overlays (where Fix puts the translator) are on
     * @param release Android's {@code ro.build.version.release}, or null while Waydroid is stopped; then the
     *                binder protocol in the config says it
     */
    static CheckResult translator(String cpuinfo, String config, String release) {
        String arch = setting(config, "waydroid", "arch");
        if (arch != null && arch.startsWith("arm")) {
            return CheckResult.ok(EmulatorCheck.ARM_TRANSLATOR, "This Android runs ARM code itself (" + arch + ").");
        }
        CpuVendor vendor = CpuVendor.of(cpuinfo);
        Translator current = Translator.ofBridgeProp(setting(config, "properties", Translator.BRIDGE_PROP));
        Translator suited = Translator.suitedTo(vendor);
        if (current == suited || (suited == Translator.UNKNOWN && current != Translator.NONE)) {
            return CheckResult.ok(EmulatorCheck.ARM_TRANSLATOR, current == suited
                    ? current.displayName() + ", suited to this " + vendor.displayName() + " processor."
                    : current.displayName() + " is installed.");
        }
        String target = suited == Translator.UNKNOWN ? Translator.LIBHOUDINI.displayName() : suited.displayName();
        String reason = current == Translator.NONE
                ? "No ARM translator: games built for phones' ARM processors won't install or start."
                : current.displayName() + " on an " + vendor.displayName() + " processor: " + target
                + " runs ARM games better here.";
        List<String> commands = List.of("git clone https://github.com/casualsnek/waydroid_script",
                "cd waydroid_script", "python3 -m venv venv", "venv/bin/pip install -r requirements.txt",
                "sudo venv/bin/python3 main.py install " + (target.equals("libndk") ? "libndk" : "libhoudini"));
        String why = cannotFix(config, androidRelease(release, config));
        if (why != null) {
            return CheckResult.byHand(EmulatorCheck.ARM_TRANSLATOR, reason, why, commands,
                    "https://github.com/casualsnek/waydroid_script");
        }
        return CheckResult.fixable(EmulatorCheck.ARM_TRANSLATOR, reason, "Fix installs " + target
                + (current == Translator.NONE ? "" : " in place of " + current.displayName())
                + " and restarts Waydroid (it asks for your password). Undo puts the old one back.");
    }

    /**
     * The translator check as it reads now, Android's version from the config: a Fix leaves the session stopped,
     * and adb has nothing to ask.
     */
    static CheckResult translatorNow() {
        return translator(read(CPUINFO), read(CONFIG), null);
    }

    /** What a translator Fix replaces with what, for which Android. */
    record TranslatorSwap(Translator target, Translator current, String release) {}

    /**
     * What this machine's translator Fix would swap; null when it can't tell.
     *
     * @param adb where Android answers, for its version, or {@code null} to take it from the config alone
     */
    static TranslatorSwap translatorSwap(AdbEndpoint adb) {
        return translatorSwap(read(CPUINFO), read(CONFIG), prop(adb, "ro.build.version.release"));
    }

    static TranslatorSwap translatorSwap(String cpuinfo, String config, String release) {
        String android = androidRelease(release, config);
        if (android == null || !Translator.pinned(android)) return null;
        Translator suited = Translator.suitedTo(CpuVendor.of(cpuinfo));
        return new TranslatorSwap(suited == Translator.UNKNOWN ? Translator.LIBHOUDINI : suited,
                Translator.ofBridgeProp(setting(config, "properties", Translator.BRIDGE_PROP)), android);
    }

    /** Why Fix cannot install a translator here, or null when it can. */
    private static String cannotFix(String config, String release) {
        if ("false".equalsIgnoreCase(setting(config, "waydroid", "mount_overlays"))) {
            return "BotMaker can't change it: Waydroid's overlays are off (mount_overlays = False in " + CONFIG
                    + "), and the translator goes there. Turn them on, or install it with waydroid_script.";
        }
        if (release == null) {
            return "BotMaker can't tell which Android this is: start Waydroid, then check again.";
        }
        if (!Translator.pinned(release)) {
            return "BotMaker has no pinned translator for Android " + release
                    + ": install it with waydroid_script.";
        }
        return null;
    }

    /**
     * Android's version: {@code release} when Waydroid answered, else the one the config's binder protocol
     * belongs to ({@code aidl3} is Android 13's LineageOS 20, {@code aidl2} Android 11's LineageOS 18).
     */
    static String androidRelease(String release, String config) {
        if (release != null && release.trim().matches("\\d+(\\.\\d+)*")) return release.trim();
        String binder = setting(config, "waydroid", "binder_protocol");
        if ("aidl3".equals(binder)) return "13";
        if ("aidl2".equals(binder)) return "11";
        return null;
    }

    /**
     * Android's framebuffer is a different size from the one the session will be hosted at, which puts a
     * scaler between the pixels a picture was cut from and the pixels a tap lands on.
     *
     * @return null when nobody asked for a size or Waydroid's own default is in force (nothing to assert)
     */
    static CheckResult resolution(WaydroidResolution actual, WaydroidResolution expected) {
        if (expected == null || actual == null) return null;
        if (actual.equals(expected)) return CheckResult.ok(EmulatorCheck.RESOLUTION, "Android is " + actual + ".");
        return CheckResult.fixable(EmulatorCheck.RESOLUTION, "Android is set to " + actual + " but the bot runs at "
                + expected + ", so taps won't land where pictures were found.", "Fix sets Waydroid's size to "
                + expected + " and restarts its session; no password.");
    }

    static CheckResult doNotDisturb() {
        return CheckResult.info(EmulatorCheck.DO_NOT_DISTURB, "BotMaker turns on Do Not Disturb while a bot runs "
                + "and restores it after.");
    }

    /** The interface the host's default route leaves by ({@code dev X} of {@code default via …}), or null. */
    static String defaultInterface(String ipRouteOutput) {
        if (ipRouteOutput == null) return null;
        for (String line : ipRouteOutput.split("\\R")) {
            if (!line.startsWith("default ")) continue;
            String[] words = line.trim().split("\\s+");
            for (int i = 0; i + 1 < words.length; i++) {
                if ("dev".equals(words[i])) return words[i + 1];
            }
        }
        return null;
    }

    /** {@code key}'s value in {@code [section]} of an ini text, trimmed; null when absent. */
    static String setting(String ini, String section, String key) {
        if (ini == null) return null;
        boolean in = false;
        for (String raw : ini.split("\\R")) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                in = line.substring(1, line.length() - 1).trim().equals(section);
            } else if (in) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).trim().equals(key)) return line.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** Android's {@code key} over a short-lived adb connection; null when it can't be asked. */
    private static String prop(AdbEndpoint adb, String key) {
        if (adb == null) return null;
        try (AdbDevice device = AdbDevice.connect(adb)) {
            return device.getProp(key);
        } catch (Throwable t) {
            // dadb surfaces some failures as Errors; a probe never propagates either kind.
            return null;
        }
    }

    /** File contents, or null when it doesn't exist or isn't readable (a probe never throws). */
    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return null;
        }
    }
}
