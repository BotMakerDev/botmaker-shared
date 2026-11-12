package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.AdbDevice;
import com.botmaker.shared.emulator.AdbEndpoint;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLiveness;
import com.botmaker.shared.emulator.EmulatorPlatform;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.EmulatorState;
import com.botmaker.shared.emulator.InstalledProduct;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.WaydroidResolution;
import com.botmaker.shared.platform.Os;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks on a Windows emulator — BlueStacks, LDPlayer, MuMu and MEmu — in the order they run: the
 * product and the instance, its ADB port and switch, the memory it asks for, then, once Android is up, an ARM
 * translator, Google Play and the screen. The model is {@link WaydroidChecks}: each probe is pure over what it
 * examines and {@link #run} reads that, so the set is testable with no emulator.
 *
 * <p>What phase 1 found decided the set (2026-11-10): LDPlayer and BlueStacks ship with ADB off; LDPlayer
 * and BlueStacks both ask for port 5555; MEmu failed to start when Windows had no memory left to commit;
 * an x86-only image cannot run an ARM-only game; BlueStacks and LDPlayer gave a portrait game a landscape screen.
 */
public final class WindowsEmulatorChecks {

    /** The products these checks are for. */
    public static final Set<PlatformId> PRODUCTS = EnumSet.of(PlatformId.BLUESTACKS, PlatformId.LDPLAYER,
            PlatformId.MUMU, PlatformId.MEMU);

    /** The products whose screen size a Fix sets (LDPlayer by its console, BlueStacks in its conf). */
    static final Set<PlatformId> SIZE_FIXED = EnumSet.of(PlatformId.LDPLAYER, PlatformId.BLUESTACKS);

    static final String STOPPED = "The instance is stopped: the checks on Android run once it is up.";

    private static final Pattern WM_SIZE = Pattern.compile("(Physical|Override) size:\\s*(\\d+)x(\\d+)");

    private WindowsEmulatorChecks() {}

    /** Every check on {@code instance}; shells out and connects adb, never on a UI thread. */
    public static List<CheckResult> run(EmulatorInstance instance, WaydroidResolution expected) {
        List<CheckResult> rows = new ArrayList<>();
        rows.add(CheckResult.ok(EmulatorCheck.EMULATOR_INSTALLED, instance.brand() + " is installed."));
        InstalledProduct product = InstalledProduct.of(instance).orElse(null);
        rows.add(version(product));
        CheckResult hypervisor = hypervisor(instance.platformId(), product, hypervisorOn());
        if (hypervisor != null) rows.add(hypervisor);
        rows.add(CheckResult.ok(EmulatorCheck.INSTANCE_EXISTS, instance.caption() + "."));
        EmulatorLiveness liveness = EmulatorLiveness.check(instance);
        WindowsSettings settings = WindowsSettings.of(instance);
        rows.add(portFree(named(liveness.clash(), instance)));
        rows.add(adb(instance.platformId(), settings.adbOn(), liveness.adbClosed())
                .withUndo(WindowsBackups.newest(instance, EmulatorCheck.ADB_ENABLED).isPresent()));
        CheckResult memory = memory(settings.ramMb(), availableMb(), liveness.state() != EmulatorState.STOPPED);
        if (memory != null) rows.add(memory);
        if (!liveness.running()) {
            rows.add(CheckResult.info(EmulatorCheck.ADB_BOOTED, STOPPED));
            CheckResult size = resolution(instance.platformId(), settings.width(), settings.height(), expected);
            addSize(rows, size, instance);
            return List.copyOf(rows);
        }
        rows.add(WaydroidChecks.booted(EmulatorReadiness.isReady(instance)));
        if (rows.getLast().failed()) {
            rows.add(CheckResult.afterAbove(EmulatorCheck.GOOGLE_PLAY));
            rows.add(CheckResult.afterAbove(EmulatorCheck.ARM_TRANSLATOR));
            return List.copyOf(rows);
        }
        Android android = Android.read(instance);
        rows.add(googlePlay(android.play()));
        rows.add(arm(android.abis()));
        int[] size = settings.hasSize() ? new int[]{settings.width(), settings.height()} : wmSize(android.wmSize());
        CheckResult screen = size == null ? null : resolution(instance.platformId(), size[0], size[1], expected);
        addSize(rows, screen, instance);
        return List.copyOf(rows);
    }

    /**
     * The rows when the bot names an instance discovery did not find: no product installed, or none has an
     * instance by that name. Empty off Windows, where these products do not run.
     */
    public static List<CheckResult> notFound(String reference) {
        if (!Os.current().isWindows() || reference == null || reference.isBlank()) return List.of();
        List<String> installed = Platforms.ALL.stream().filter(p -> PRODUCTS.contains(p.id()))
                .filter(EmulatorPlatform::isInstalled).map(p -> p.id().displayName()).toList();
        return notFound(reference, installed);
    }

    static List<CheckResult> notFound(String reference, List<String> installed) {
        if (installed.isEmpty()) {
            Optional<TestedVersions.Tested> pinned = TestedVersions.installable().stream().findFirst();
            CheckResult none = pinned.map(tested -> CheckResult.fixable(EmulatorCheck.EMULATOR_INSTALLED,
                    "No Android emulator is installed.", "Fix installs " + tested.product() + " " + tested.version()
                            + ", the version BotMaker is tested on (" + sizeOf(tested) + " from its maker's site), "
                            + "behind one Windows permission prompt; then create an instance in it."))
                    .orElse(CheckResult.byHand(EmulatorCheck.EMULATOR_INSTALLED, "No Android emulator is installed.",
                            "Install BlueStacks, LDPlayer, MuMu Player or MEmu from its own site, then "
                                    + "create an instance in it.", List.of(), null));
            return List.of(none, CheckResult.afterAbove(EmulatorCheck.INSTANCE_EXISTS));
        }
        return List.of(CheckResult.ok(EmulatorCheck.EMULATOR_INSTALLED, String.join(", ", installed)
                        + (installed.size() == 1 ? " is" : " are") + " installed."),
                CheckResult.byHand(EmulatorCheck.INSTANCE_EXISTS, "No instance is named \"" + reference + "\".",
                        "Create it in " + String.join(" or ", installed) + "'s instance manager, or pick another "
                                + "instance as the game's emulator.", List.of(), null));
    }

    // --- the probes: pure over what they examine ---

    /** The installed version against the tested one: older fails, newer only says so; neither stops a launch. */
    static CheckResult version(InstalledProduct product) {
        if (product == null) return CheckResult.info(EmulatorCheck.EMULATOR_VERSION, "BotMaker can't find this "
                + "emulator's entry in Apps & features, so it can't tell its version.");
        Optional<TestedVersions.Tested> found = TestedVersions.forKey(product.key());
        if (found.isEmpty()) return CheckResult.info(EmulatorCheck.EMULATOR_VERSION, product.name()
                + (product.version() == null ? "" : " " + product.version())
                + ": BotMaker was not tested on this edition.");
        TestedVersions.Tested tested = found.get();
        if (product.version() == null) return CheckResult.info(EmulatorCheck.EMULATOR_VERSION, product.name()
                + " doesn't say its version; BotMaker is tested on " + tested.version() + ".");
        String now = product.name() + " " + product.version();
        int order = TestedVersions.compare(product.version(), tested.version());
        if (order == 0) return CheckResult.ok(EmulatorCheck.EMULATOR_VERSION, now + ", the version BotMaker is "
                + "tested on.");
        if (order > 0) return CheckResult.info(EmulatorCheck.EMULATOR_VERSION, now + " is newer than the tested "
                + tested.version() + ": it should work, and if a check or a run fails here, " + tested.version()
                + " is the one known to work.");
        String reason = now + " is older than " + tested.version() + ", the version BotMaker is tested on.";
        if (tested.installer() != null) {
            return CheckResult.fixable(EmulatorCheck.EMULATOR_VERSION, reason, "Fix downloads " + tested.version()
                    + " (" + sizeOf(tested) + " from its maker's site) and installs it over this one, behind one "
                    + "Windows permission prompt. Close its instances first; they are kept.");
        }
        return CheckResult.byHand(EmulatorCheck.EMULATOR_VERSION, reason, "Install " + tested.product() + " "
                + tested.version() + " or newer from its maker's site.", List.of(), tested.downloadPage());
    }

    private static String sizeOf(TestedVersions.Tested tested) {
        return tested.installer().remote().size() / (1024 * 1024) + " MB";
    }

    /**
     * Whether the product runs beside Windows' hypervisor (Hyper-V, WSL 2, Memory Integrity), which takes the CPU's
     * virtualisation for itself. Null when Windows did not say. A version at or above the tested one was tested
     * with it on (every product, 2026-11-10/11); LDPlayer before 9 has no Hyper-V build and fails; any other
     * version is only said.
     */
    static CheckResult hypervisor(PlatformId platform, InstalledProduct product, Boolean on) {
        if (on == null) return null;
        if (!on) return CheckResult.ok(EmulatorCheck.HYPERVISOR, "Windows' hypervisor is off: every emulator build "
                + "runs.");
        String name = product == null ? platform.displayName() : product.name()
                + (product.version() == null ? "" : " " + product.version());
        Optional<TestedVersions.Tested> tested = product == null ? Optional.empty()
                : TestedVersions.forKey(product.key());
        if (tested.isPresent() && product.version() != null
                && TestedVersions.compare(product.version(), tested.get().version()) >= 0) {
            return CheckResult.ok(EmulatorCheck.HYPERVISOR, "Windows' hypervisor is on, and " + name + " was tested "
                    + "with it on.");
        }
        if (platform == PlatformId.LDPLAYER && product != null && product.version() != null
                && TestedVersions.major(product.version()) >= 0 && TestedVersions.major(product.version()) < 9) {
            return CheckResult.byHand(EmulatorCheck.HYPERVISOR, "Windows' hypervisor is on, and " + name + " can't "
                    + "run beside it: LDPlayer runs on Hyper-V from version 9.", "Install LDPlayer 14 from its site "
                    + "(its instances are its own: recreate them there), or turn off Hyper-V, WSL and Memory "
                    + "Integrity in Windows.", List.of(),
                    "https://www.ldplayer.net/");
        }
        return CheckResult.info(EmulatorCheck.HYPERVISOR, "Windows' hypervisor is on, and " + name + " was not "
                + "tested with it on: if an instance won't start, install the tested version.");
    }

    private static volatile Boolean hypervisorOn;
    private static volatile boolean hypervisorAsked;

    /**
     * {@code Win32_ComputerSystem.HypervisorPresent}, asked once: it changes only on a restart, and a slow WMI must
     * not hold every later run of the checks. Null if unknown.
     */
    static Boolean hypervisorOn() {
        if (hypervisorAsked || !Os.current().isWindows()) return hypervisorOn;
        hypervisorAsked = true;
        try {
            ElevatedCopy.Ran ran = ElevatedCopy.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-CimInstance Win32_ComputerSystem).HypervisorPresent"), Duration.ofSeconds(20));
            String said = ran.finished() && ran.exit() == 0 ? ran.said().trim() : "";
            if (said.equalsIgnoreCase("True")) hypervisorOn = true;
            else if (said.equalsIgnoreCase("False")) hypervisorOn = false;
            return hypervisorOn;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code clash} with "another program" replaced by the program listening on the instance's port, which is not
     * always an emulator's instance: MuMu's {@code MuMuNxDevice.exe} listens on 5555 while its own adb is 16384.
     */
    private static String named(String clash, EmulatorInstance instance) {
        if (clash == null || !clash.endsWith(ANOTHER) || !(instance.adb() instanceof AdbEndpoint.Tcp tcp)) return clash;
        try {
            ElevatedCopy.Ran netstat = ElevatedCopy.run(List.of("netstat", "-ano", "-p", "TCP"), Duration.ofSeconds(10));
            long pid = netstat.finished() ? listener(netstat.said(), tcp.port()) : -1;
            String program = pid < 0 ? null : ProcessHandle.of(pid).flatMap(p -> p.info().command())
                    .map(command -> Path.of(command).getFileName().toString()).orElse(null);
            return program == null ? clash : clash.substring(0, clash.length() - ANOTHER.length()) + program;
        } catch (Exception e) {
            return clash;
        }
    }

    private static final String ANOTHER = "another program";

    /**
     * The pid listening on {@code port} in {@code netstat -ano -p TCP}'s output, or -1. A listener is the row whose
     * remote address is {@code 0.0.0.0:0}: its state word is in the system's language.
     */
    static long listener(String netstat, int port) {
        for (String line : netstat.split("\\R")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length >= 5 && cols[0].equals("TCP") && cols[1].endsWith(":" + port)
                    && cols[2].equals("0.0.0.0:0") && cols[cols.length - 1].matches("\\d+")) {
                return Long.parseLong(cols[cols.length - 1]);
            }
        }
        return -1;
    }

    static CheckResult portFree(String clash) {
        if (clash == null) return CheckResult.ok(EmulatorCheck.PORT_FREE, "No other emulator holds its port.");
        return CheckResult.byHand(EmulatorCheck.PORT_FREE, capital(clash) + ".", "Only one of them can run at a "
                + "time: close the other, or give one of them another ADB port in its settings.", List.of(), null);
    }

    /**
     * @param adbOn     the product's file's switch, or {@code null} when it keeps none
     * @param adbClosed the product says Android is up and nothing answers on its port
     */
    static CheckResult adb(PlatformId platform, Boolean adbOn, boolean adbClosed) {
        String setting = platform.adbSetting();
        if (Boolean.FALSE.equals(adbOn) && WindowsSettings.adbInFile(platform)) {
            return CheckResult.fixable(EmulatorCheck.ADB_ENABLED, platform.displayName() + " has ADB turned off, so "
                    + "BotMaker can't reach Android.", "Fix turns it on in " + platform.displayName() + "'s settings "
                    + "file, stopping the instance first and starting it again.");
        }
        if (adbClosed) {
            return CheckResult.byHand(EmulatorCheck.ADB_ENABLED, "Android is up but nothing answers on its ADB port.",
                    setting == null ? "Restart the instance; if it goes on, ADB is off in " + platform.displayName()
                            + "'s settings." : "Turn on ADB in " + platform.displayName() + ": " + setting + ".",
                    List.of(), null);
        }
        if (adbOn == null && WindowsSettings.adbInFile(platform)) {
            return CheckResult.info(EmulatorCheck.ADB_ENABLED, "BotMaker could not read " + platform.displayName()
                    + "'s settings file, so it can't tell whether ADB is on"
                    + (setting == null ? "." : "; it is in " + platform.displayName() + ": " + setting + "."));
        }
        return CheckResult.ok(EmulatorCheck.ADB_ENABLED, adbOn == null ? "ADB is always on." : "ADB is on.");
    }

    /**
     * Null when the file names no memory, or the instance already runs with what it asked for. Windows refuses to
     * start a VM it can't commit memory to (MEmu's VERR_UNRESOLVED_ERROR, Win32 1455, 2026-11-10).
     *
     * @param availableMb what Windows can still commit, or -1 when unknown
     */
    static CheckResult memory(int ramMb, long availableMb, boolean started) {
        if (ramMb <= 0 || started || availableMb < 0) return null;
        if (ramMb <= availableMb) {
            return CheckResult.ok(EmulatorCheck.HOST_MEMORY, "It asks for " + ramMb + " MB; Windows has "
                    + availableMb + " MB to give.");
        }
        return CheckResult.byHand(EmulatorCheck.HOST_MEMORY, "It asks for " + ramMb + " MB and Windows has only "
                + availableMb + " MB left to give: it would fail to start.", "Close another emulator or a large "
                + "program, or lower the instance's memory in its settings.", List.of(), null);
    }

    /** @param play whether the Play Store is installed, or {@code null} when Android did not answer */
    static CheckResult googlePlay(Boolean play) {
        if (play == null) return CheckResult.info(EmulatorCheck.GOOGLE_PLAY, "Android did not say whether Google "
                + "Play is installed.");
        return play ? CheckResult.ok(EmulatorCheck.GOOGLE_PLAY, "Google Play is installed.")
                : CheckResult.info(EmulatorCheck.GOOGLE_PLAY, "No Google Play: games install from files, as "
                + "BotMaker's library does.");
    }

    /** {@code ro.product.cpu.abilist}: an ARM entry means ARM apps run, natively or translated. */
    static CheckResult arm(String abis) {
        if (abis == null || abis.isBlank()) {
            return CheckResult.info(EmulatorCheck.ARM_TRANSLATOR, "Android did not say which processors it runs "
                    + "apps for.");
        }
        if (abis.contains("arm")) return CheckResult.ok(EmulatorCheck.ARM_TRANSLATOR, "Runs ARM apps (" + abis.trim()
                + ").");
        return CheckResult.byHand(EmulatorCheck.ARM_TRANSLATOR, "This image runs x86 apps only (" + abis.trim()
                        + "): a game built for ARM, as most Unity games are, won't install.",
                "Create an instance on an Android version that runs ARM apps (64-bit, Android 9 or later).",
                List.of(), null);
    }

    /** Null without an expected size, or a size neither the file nor Android gives. */
    static CheckResult resolution(PlatformId platform, int width, int height, WaydroidResolution expected) {
        if (expected == null || width <= 0 || height <= 0) return null;
        String now = width + "x" + height;
        if (width == expected.width() && height == expected.height()) {
            return CheckResult.ok(EmulatorCheck.RESOLUTION, now + ", the bot's size.");
        }
        String reason = "The screen is " + now + "; the bot's pictures are " + expected + ".";
        if (SIZE_FIXED.contains(platform)) {
            return CheckResult.fixable(EmulatorCheck.RESOLUTION, reason, "Fix sets it to " + expected + ", stopping "
                    + "the instance first and starting it again.");
        }
        return CheckResult.byHand(EmulatorCheck.RESOLUTION, reason, "In " + platform.displayName() + "'s settings, "
                + "set the screen to " + expected.width() + " × " + expected.height() + ".", List.of(), null);
    }

    /** {@code wm size}: the override when one is set, else the physical size; null when it says neither. */
    static int[] wmSize(String output) {
        if (output == null) return null;
        int[] size = null;
        Matcher m = WM_SIZE.matcher(output);
        while (m.find()) {
            size = new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            if (m.group(1).equals("Override")) break;
        }
        return size;
    }

    /**
     * The size row, offering Undo while a size Fix's backup is left; with no size to judge (no bot size, none read)
     * a backup still gets its row, so its Undo stays reachable.
     */
    private static void addSize(List<CheckResult> rows, CheckResult size, EmulatorInstance instance) {
        boolean backup = WindowsBackups.newest(instance, EmulatorCheck.RESOLUTION).isPresent();
        if (size != null) rows.add(size.withUndo(backup));
        else if (backup) rows.add(CheckResult.info(EmulatorCheck.RESOLUTION, "BotMaker set this instance's screen "
                + "size; Undo puts its own back.").withUndo(true));
    }

    private static String capital(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** What Windows can still commit, in MB: the page file included, which is what starting a VM needs. */
    static long availableMb() {
        try {
            WinBase.MEMORYSTATUSEX status = new WinBase.MEMORYSTATUSEX();
            if (!Kernel32.INSTANCE.GlobalMemoryStatusEx(status)) return -1;
            return status.ullAvailPageFile.longValue() / (1024 * 1024);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** What the checks read from a running Android, over one adb connection. */
    private record Android(String abis, Boolean play, String wmSize) {

        static Android read(EmulatorInstance instance) {
            try (AdbDevice device = AdbDevice.connect(instance.adb())) {
                String vending = device.shell("pm path com.android.vending");
                return new Android(device.getProp("ro.product.cpu.abilist"),
                        vending != null && vending.contains("package:"), device.shell("wm size"));
            } catch (Throwable t) {
                // dadb surfaces some failures as Errors; a probe never propagates either kind.
                return new Android(null, null, null);
            }
        }
    }
}
