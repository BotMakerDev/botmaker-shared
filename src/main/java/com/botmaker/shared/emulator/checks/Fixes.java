package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.AndroidSdk;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLiveness;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.PlayStoreSetup;
import com.botmaker.shared.emulator.WaydroidCli;
import com.botmaker.shared.emulator.WaydroidResolution;
import com.botmaker.shared.emulator.WaydroidStatus;
import com.botmaker.shared.launch.UriLauncher;
import com.botmaker.shared.tools.Downloads;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Which {@link Fix} repairs a failed check. Building one reads nothing and runs nothing: each step reads what it
 * needs when it starts, so the row can build it on any thread.
 */
public final class Fixes {

    /** Starts Waydroid's session, as ▶ Launch game does; returns null when it worked, else why not. */
    @FunctionalInterface
    public interface Starter {
        String start(Consumer<String> words);
    }

    /**
     * What the fixes need from their caller.
     *
     * @param instance the emulator, or {@code null} when Waydroid's was not discovered
     * @param expected the size the bot runs at, or {@code null}
     * @param starter  how a session starts: only the caller knows the display a bot's Waydroid nests in
     */
    public record Context(EmulatorInstance instance, WaydroidResolution expected, Starter starter) {}

    private Fixes() {}

    /** The Fix for {@code result}; empty when its row offers none. */
    public static Optional<Fix> of(CheckResult result, Context context) {
        if (!result.failed() || !result.fixable()) return Optional.empty();
        if (windows(context)) {
            return result.check() == EmulatorCheck.ADB_BOOTED ? Optional.of(booted(context))
                    : WindowsFixes.of(result, context.instance(), context.expected());
        }
        return switch (result.check()) {
            case WAYDROID_RUNNING -> Optional.of(running(context));
            case ADB_BOOTED -> Optional.of(booted(context));
            case INTERNET -> Optional.of(internet());
            case GOOGLE_PLAY -> Optional.of(googlePlay(context));
            case ARM_TRANSLATOR -> Optional.of(translator(context));
            case RESOLUTION -> context.expected() == null ? Optional.empty() : Optional.of(resolution(context));
            case AVD_MEMORY -> context.instance() == null ? Optional.empty() : Optional.of(memory(context));
            default -> Optional.empty();
        };
    }

    /**
     * Puts back what the newest Fix of {@code check} changed: a Windows emulator's settings file, else Waydroid's
     * translator. Empty when no backup is left.
     */
    public static Optional<Fix> undo(Context context, EmulatorCheck check) {
        if (windows(context)) return WindowsFixes.undo(context.instance(), check);
        return undo(Backups.newest());
    }

    private static boolean windows(Context context) {
        return context.instance() != null && WindowsEmulatorChecks.PRODUCTS.contains(context.instance().platformId());
    }

    static Optional<Fix> undo(Optional<Path> backup) {
        return backup.map(path -> new Fix(EmulatorCheck.ARM_TRANSLATOR, List.of(new Fix.AsRoot(
                "Putting the old translator back", () -> TranslatorScripts.undo(path))), null));
    }

    private static Fix running(Context context) {
        return new Fix(EmulatorCheck.WAYDROID_RUNNING, List.of(
                new Fix.AsRoot("Starting the waydroid-container service", () -> "active".equals(containerState())
                        ? null : "set -e\nsystemctl start waydroid-container\n"),
                new Fix.AsUser("Starting Waydroid", words -> context.starter().start(words))),
                () -> WaydroidChecks.running(containerState(), WaydroidCli.runAnyExit("journalctl", "-u",
                        "waydroid-container", "-n", "20", "-o", "cat", "--no-pager"), WaydroidStatus.read()));
    }

    private static Fix booted(Context context) {
        return new Fix(EmulatorCheck.ADB_BOOTED, List.of(new Fix.AsUser(
                "Connecting adb and waiting for Android to finish starting", words -> {
            EmulatorInstance instance = waydroid(context);
            return instance == null || EmulatorReadiness.awaitReady(instance, instance.platformId().bootTimeout())
                    .isEmpty() ? "Android did not finish starting." : null;
        })), () -> {
            EmulatorInstance instance = waydroid(context);
            return WaydroidChecks.booted(instance != null && EmulatorReadiness.isReady(instance));
        });
    }

    private static Fix internet() {
        return new Fix(EmulatorCheck.INTERNET, List.of(new Fix.AsRoot("Routing Android's traffic out of this machine",
                () -> internetScript(WaydroidChecks.defaultInterface(WaydroidCli.run("ip", "route"))))),
                () -> WaydroidChecks.internet(WaydroidChecks.read(WaydroidChecks.IP_FORWARD),
                        WaydroidCli.run("ip", "route")));
    }

    /**
     * Two password windows, the one Fix that asks twice: the image is replaced before Android runs, and the ID
     * Google asks for can only be read as root once Android has started with that image. Android's own adb
     * can't read it (measured 2026-11-02: no {@code su}, and the provider refuses the shell user).
     */
    private static Fix googlePlay(Context context) {
        AtomicReference<String> id = new AtomicReference<>();
        return new Fix(EmulatorCheck.GOOGLE_PLAY, List.of(
                new Fix.AsRoot("Re-initialising Waydroid with Google Play", Fixes::playScript),
                new Fix.AsUser("Starting Waydroid", words -> context.starter().start(words)),
                new Fix.AsRoot("Reading the Android ID Google asks for", Fixes::androidIdScript,
                        line -> {
                            if (line.startsWith(ID_LINE)) id.set(line.substring(ID_LINE.length()).trim());
                        }),
                new Fix.AsUser("Opening Google's registration page", words -> {
                    if (id.get() == null) return "Waydroid did not say its Android ID.";
                    UriLauncher.open(PlayStoreSetup.REGISTER);
                    words.accept("Enter " + id.get() + " on Google's page (" + PlayStoreSetup.REGISTER + "), wait a "
                            + "few minutes, then stop and start Waydroid.");
                    return null;
                })),
                () -> WaydroidChecks.googlePlay(WaydroidChecks.hasPlay(WaydroidChecks.read(WaydroidChecks.CONFIG))));
    }

    private static Fix translator(Context context) {
        AtomicReference<TranslatorPins.Archive> prebuilts = new AtomicReference<>();
        AtomicReference<WaydroidChecks.TranslatorSwap> swap = new AtomicReference<>();
        return new Fix(EmulatorCheck.ARM_TRANSLATOR, List.of(
                new Fix.AsUser("Downloading the translator", words -> {
                    EmulatorInstance instance = waydroid(context);
                    WaydroidChecks.TranslatorSwap wanted = WaydroidChecks.translatorSwap(
                            instance == null ? null : instance.adb());
                    if (wanted == null) return "BotMaker can't tell which translator this Android needs.";
                    swap.set(wanted);
                    Optional<TranslatorPins.Archive> folder = TranslatorPins.downloaded(wanted.target(),
                            wanted.release(), percent(words, wanted.target()));
                    if (folder.isEmpty()) return "The " + wanted.target().displayName() + " download failed or did "
                            + "not match its checksum: nothing was changed.";
                    prebuilts.set(folder.get());
                    return null;
                }),
                new Fix.AsRoot("Installing the translator", () -> TranslatorScripts.install(swap.get().target(),
                        swap.get().current(), prebuilts.get(), Backups.stamp(LocalDateTime.now())))),
                WaydroidChecks::translatorNow);
    }

    private static Fix resolution(Context context) {
        WaydroidResolution expected = context.expected();
        return new Fix(EmulatorCheck.RESOLUTION, List.of(new Fix.AsUser("Setting Waydroid's size to " + expected,
                words -> expected.apply() ? null : "Waydroid did not take the size " + expected + ".")),
                () -> WaydroidChecks.resolution(WaydroidResolution.read(), expected));
    }

    private static Fix memory(Context context) {
        EmulatorInstance instance = context.instance();
        Path config = AvdChecks.configIni(AndroidSdk.avdHome(), instance.name());
        return new Fix(EmulatorCheck.AVD_MEMORY, List.of(new Fix.AsUser("Setting its memory to " + AvdChecks.MIN_RAM_MB
                + " MB", words -> setMemory(config, EmulatorLiveness.running(instance)))),
                () -> {
                    String text = WaydroidChecks.read(config);
                    return text == null ? null : AvdChecks.memory(text);
                });
    }

    /** Null when {@code config} now gives the emulator enough memory, else why not. */
    static String setMemory(Path config, boolean running) throws Exception {
        if (running) return "Stop the emulator first: it reads its memory only when it starts.";
        Files.writeString(config, AvdChecks.withMemory(Files.readString(config), AvdChecks.MIN_RAM_MB));
        return null;
    }

    /** The word Android's ID comes after in the script's output. */
    private static final String ID_LINE = "ANDROID_ID ";

    static String playScript() {
        return "set -e\n"
                + "echo 'STEP 1/3 Stopping the Waydroid session'\n"
                + "waydroid session stop </dev/null >/dev/null 2>&1 || true\n"
                + "echo 'STEP 2/3 Downloading the Google Play image'\n"
                + "waydroid init -s GAPPS -f </dev/null\n"
                + "echo 'STEP 3/3 Restarting the container'\n"
                + "systemctl restart waydroid-container\n";
    }

    /** Google's services write the ID a little after Android starts: up to two minutes. */
    static String androidIdScript() {
        return "i=0\n"
                + "while [ \"$i\" -lt 60 ]; do\n"
                + "  id=$(waydroid shell -- sqlite3 " + PlayStoreSetup.GSERVICES_DB + " \""
                + PlayStoreSetup.ANDROID_ID_QUERY + "\" </dev/null 2>/dev/null | tr -d '\\r\\n ' || true)\n"
                + "  case \"$id\" in\n"
                + "    ''|*[!0-9]*) ;;\n"
                + "    *) echo \"" + ID_LINE + "$id\"; exit 0 ;;\n"
                + "  esac\n"
                + "  sleep 2\n"
                + "  i=$((i + 1))\n"
                + "done\n"
                + "echo 'Google services did not give an Android ID: is Android still starting?' >&2\n"
                + "exit 3\n";
    }

    /**
     * {@link WaydroidChecks#internetCommands} as a script. The interface comes from {@code ip route} and is put
     * in a root script, so it has to look like one.
     */
    static String internetScript(String iface) {
        if (iface == null || !iface.matches("[A-Za-z0-9_.:@-]+")) {
            return "echo 'This machine has no default route to send Android'\"'\"'s traffic out of.' >&2\nexit 2\n";
        }
        List<String> commands = WaydroidChecks.routeCommands(iface);
        // The firewall is down while the rule goes in, and back up however the script ends, only if it was up: a
        // signal (Studio gone, a closed pipe) runs the EXIT trap too, and a firewall that stays down is an error.
        // --force: `ufw enable` may ask for a yes, and its stdin is the rest of this script.
        StringBuilder sh = new StringBuilder("set -e\n")
                .append("trap 'exit 129' HUP\ntrap 'exit 130' INT\ntrap 'exit 141' PIPE\ntrap 'exit 143' TERM\n")
                .append("case \"$(ufw status 2>/dev/null || true)\" in\n")
                .append("  *'Status: active'*)\n")
                .append("    trap 'ufw --force enable >/dev/null 2>&1 </dev/null || { echo \"The firewall could not "
                        + "be turned back on: run ufw enable.\" >&2; exit 4; }' EXIT\n")
                .append("    ufw disable >/dev/null </dev/null ;;\n")
                .append("esac\n");
        for (int i = 0; i < commands.size(); i++) {
            sh.append("echo 'STEP ").append(i + 1).append('/').append(commands.size())
                    .append(" Setting up the route'\n").append(commands.get(i)).append('\n');
        }
        return sh.toString();
    }

    /** The instance to reach Android by: the discovered one, else where Waydroid says it is. */
    private static EmulatorInstance waydroid(Context context) {
        if (context.instance() != null) return context.instance();
        WaydroidStatus status = WaydroidStatus.read();
        String ip = status == null ? null : status.ipAddress();
        return ip == null || ip.isBlank() ? null
                : new EmulatorInstance(PlatformId.WAYDROID, "Waydroid", ip, WaydroidStatus.ADB_PORT);
    }

    private static String containerState() {
        String state = WaydroidCli.runAnyExit("systemctl", "is-active", "waydroid-container");
        return state == null ? "" : state.trim();
    }

    private static Downloads.Progress percent(Consumer<String> words, Translator target) {
        int[] shown = {-1};
        return (bytes, total) -> {
            int now = total <= 0 ? -1 : (int) (bytes * 100 / total);
            if (now == shown[0]) return;
            shown[0] = now;
            words.accept("Downloading " + target.displayName() + (now < 0 ? "" : ": " + now + "%"));
        };
    }
}
