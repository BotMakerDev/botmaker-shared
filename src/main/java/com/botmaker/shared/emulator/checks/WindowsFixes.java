package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLauncher;
import com.botmaker.shared.emulator.EmulatorLiveness;
import com.botmaker.shared.emulator.EmulatorReadiness;
import com.botmaker.shared.emulator.EmulatorState;
import com.botmaker.shared.emulator.InstalledProduct;
import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.emulator.WaydroidResolution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Fixes of {@link WindowsEmulatorChecks}. Each one is the same four steps: stop the instance (a product reads
 * its file only when an instance starts, and writes it back when one stops), keep the file in {@link WindowsBackups},
 * make the one change, and start the instance again if it was running. A file Windows protects is written through
 * one UAC prompt ({@link ElevatedCopy}). Building one reads nothing.
 *
 * <p>Undo puts back only what its own check changed, read from the backup: the ADB switch, or the screen size. The
 * file is never copied back whole, since one file holds both checks' settings (and, for BlueStacks, every instance's),
 * and a later Fix or the product's own window may have changed the rest.
 */
public final class WindowsFixes {

    /** How long a stop may take before the Fix gives up rather than write a file the product still holds. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    /** LDPlayer's screen when its file names none: what a new instance gets. */
    private static final WaydroidResolution LDPLAYER_SIZE = new WaydroidResolution(1600, 900);

    private WindowsFixes() {}

    /** The Fix for {@code result} on {@code instance}; empty when its row offers none. */
    public static Optional<Fix> of(CheckResult result, EmulatorInstance instance, WaydroidResolution expected) {
        if (instance == null || !result.failed() || !result.fixable()) return Optional.empty();
        PlatformId platform = instance.platformId();
        return switch (result.check()) {
            case ADB_ENABLED -> WindowsSettings.adbInFile(platform) ? Optional.of(adb(instance)) : Optional.empty();
            case RESOLUTION -> expected != null && WindowsEmulatorChecks.SIZE_FIXED.contains(platform)
                    ? Optional.of(size(instance, expected)) : Optional.empty();
            case EMULATOR_VERSION -> InstalledProduct.of(instance)
                    .flatMap(product -> TestedVersions.forKey(product.key()))
                    .filter(tested -> tested.installer() != null)
                    .map(tested -> WindowsInstallers.install(tested, instance,
                            () -> WindowsInstallers.versionNow(instance)));
            default -> Optional.empty();
        };
    }

    /** The Fix of "no emulator installed": the first product BotMaker can install. Empty when none is pinned. */
    public static Optional<Fix> install() {
        return TestedVersions.installable().stream().findFirst().map(tested -> WindowsInstallers.install(tested, null,
                () -> WindowsInstallers.installed(tested)
                        ? CheckResult.ok(EmulatorCheck.EMULATOR_INSTALLED, tested.product() + " is installed.")
                        : CheckResult.byHand(EmulatorCheck.EMULATOR_INSTALLED, tested.product() + " did not install.",
                        "Install it from its maker's site.", List.of(), tested.downloadPage())));
    }

    /** Puts back what the newest Fix of {@code check} on {@code instance} changed; empty with no backup left. */
    public static Optional<Fix> undo(EmulatorInstance instance, EmulatorCheck check) {
        if (check != EmulatorCheck.ADB_ENABLED && check != EmulatorCheck.RESOLUTION) return Optional.empty();
        return WindowsBackups.newest(instance, check).map(backup -> new Fix(check, around(instance, null,
                new Fix.AsUser(check == EmulatorCheck.ADB_ENABLED ? "Putting ADB back as it was"
                        : "Putting the screen size back", words -> {
                    WindowsSettings before = WindowsSettings.parse(instance, backup.copy(),
                            ElevatedCopy.read(backup.copy()));
                    String failed = check == EmulatorCheck.ADB_ENABLED
                            ? writeAdb(instance, backup.origin(), Boolean.TRUE.equals(before.adbOn()))
                            : putSize(instance, backup.origin(), sizeOf(instance.platformId(), before), before.dpi());
                    if (failed == null) WindowsBackups.undone(backup);
                    return failed;
                })), null));
    }

    /** The size {@code settings} name; for LDPlayer, a file naming none had a new instance's. */
    private static WaydroidResolution sizeOf(PlatformId platform, WindowsSettings settings) {
        if (settings.hasSize()) return new WaydroidResolution(settings.width(), settings.height());
        return platform == PlatformId.LDPLAYER ? LDPLAYER_SIZE : null;
    }

    private static Fix adb(EmulatorInstance instance) {
        PlatformId platform = instance.platformId();
        AtomicReference<Path> file = new AtomicReference<>();
        return new Fix(EmulatorCheck.ADB_ENABLED, around(instance, backup(instance, EmulatorCheck.ADB_ENABLED, file),
                new Fix.AsUser("Turning on ADB", words -> writeAdb(instance, file.get(), true))),
                () -> WindowsEmulatorChecks.adb(platform, WindowsSettings.of(instance).adbOn(), false));
    }

    private static String writeAdb(EmulatorInstance instance, Path file, boolean on) throws Exception {
        String text = ElevatedCopy.read(file);
        return ElevatedCopy.write(file, instance.platformId() == PlatformId.LDPLAYER
                ? WindowsSettings.ldPlayerWithAdb(text, on)
                : WindowsSettings.blueStacksWith(text, "bst.enable_adb_access", on ? "1" : "0"));
    }

    private static Fix size(EmulatorInstance instance, WaydroidResolution expected) {
        PlatformId platform = instance.platformId();
        AtomicReference<Path> file = new AtomicReference<>();
        return new Fix(EmulatorCheck.RESOLUTION, around(instance, backup(instance, EmulatorCheck.RESOLUTION, file),
                new Fix.AsUser("Setting the screen to " + expected,
                        words -> putSize(instance, file.get(), expected, WindowsSettings.of(instance).dpi()))),
                () -> {
                    WindowsSettings now = WindowsSettings.of(instance);
                    return WindowsEmulatorChecks.resolution(platform, now.width(), now.height(), expected);
                });
    }

    private static String putSize(EmulatorInstance instance, Path file, WaydroidResolution size, int dpi)
            throws Exception {
        if (size == null) return "The copy BotMaker kept names no screen size to put back.";
        return instance.platformId() == PlatformId.LDPLAYER
                ? ldPlayerSize(instance, size, dpi > 0 ? dpi : WindowsSettings.LDPLAYER_DPI)
                : blueStacksSize(file, WindowsSettings.token(instance), size);
    }

    /** {@code ldconsole modify --index <i> --resolution w,h,dpi}. */
    private static String ldPlayerSize(EmulatorInstance instance, WaydroidResolution size, int dpi) throws Exception {
        List<String> command = ldPlayerSizeCommand(instance.launchCommand(), size, dpi);
        if (command == null) return "BotMaker can't find LDPlayer's console for this instance.";
        ElevatedCopy.Ran ran = ElevatedCopy.run(command, Duration.ofMinutes(1));
        if (!ran.finished()) return "LDPlayer's console did not answer.";
        return ran.exit() == 0 ? null : "LDPlayer's console refused the size" + (ran.said().isEmpty() ? "."
                : ": " + ran.said());
    }

    /** The console command from the instance's launch command ({@code ldconsole launch --index i}); null without. */
    static List<String> ldPlayerSizeCommand(List<String> launch, WaydroidResolution size, int dpi) {
        int at = launch.indexOf("--index");
        if (launch.isEmpty() || at < 0 || at + 1 >= launch.size()) return null;
        return List.of(launch.getFirst(), "modify", "--index", launch.get(at + 1), "--resolution",
                size.width() + "," + size.height() + "," + dpi);
    }

    private static String blueStacksSize(Path conf, String token, WaydroidResolution size) throws Exception {
        if (token == null) return "BotMaker can't tell which BlueStacks instance this is.";
        String text = ElevatedCopy.read(conf);
        String prefix = "bst.instance." + token + ".";
        text = WindowsSettings.blueStacksWith(text, prefix + "fb_width", String.valueOf(size.width()));
        text = WindowsSettings.blueStacksWith(text, prefix + "fb_height", String.valueOf(size.height()));
        return ElevatedCopy.write(conf, text);
    }

    /** The step that keeps the instance's file before the change; {@code file} receives where it is. */
    private static Fix.Step backup(EmulatorInstance instance, EmulatorCheck check, AtomicReference<Path> file) {
        return new Fix.AsUser("Keeping a copy of its settings", words -> {
            Optional<Path> found = WindowsSettings.fileOf(instance);
            if (found.isEmpty() || !Files.isReadable(found.get())) {
                return "BotMaker can't find " + instance.brand() + "'s settings file for this instance.";
            }
            file.set(found.get());
            WindowsBackups.save(instance, check, found.get());
            return null;
        });
    }

    /**
     * Stop, the steps given ({@code before} may be null), start again: the shape of every Fix here. The instance is
     * started again only if it was up, so a Fix on a stopped instance leaves it stopped.
     */
    private static List<Fix.Step> around(EmulatorInstance instance, Fix.Step before, Fix.Step change) {
        AtomicBoolean wasUp = new AtomicBoolean();
        List<Fix.Step> steps = new ArrayList<>();
        steps.add(new Fix.AsUser("Stopping " + instance.name(), words -> {
            String sharing = othersRunning(instance);
            if (sharing != null) return sharing;
            boolean up = EmulatorLiveness.check(instance).state() != EmulatorState.STOPPED;
            wasUp.set(up);
            return up ? stop(instance) : null;
        }));
        if (before != null) steps.add(before);
        steps.add(change);
        steps.add(new Fix.AsUser("Starting " + instance.name() + " again", words -> {
            if (!wasUp.get()) return null;
            if (!EmulatorLauncher.launch(instance)) return instance.brand() + "'s start command did not run.";
            return EmulatorReadiness.bringUp(instance, words).isPresent() ? null : EmulatorReadiness.notReady(instance);
        }));
        return steps;
    }

    /**
     * Why the Fix can't go on while another instance sharing the file runs (each BlueStacks player writes its
     * edition's conf back as it closes, over the change), or null.
     */
    private static String othersRunning(EmulatorInstance instance) {
        Optional<Path> file = WindowsSettings.fileOf(instance);
        if (file.isEmpty()) return null;
        List<EmulatorInstance> now = Platforms.recent(Duration.ofSeconds(2));
        List<String> running = now.stream()
                .filter(other -> other.platformId() == instance.platformId()
                        && !other.identity().equals(instance.identity())
                        && file.equals(WindowsSettings.fileOf(other))
                        && EmulatorLiveness.check(other, now).state() != EmulatorState.STOPPED)
                .map(EmulatorInstance::name).toList();
        return running.isEmpty() ? null : "Close " + String.join(", ", running) + " first: it keeps the same settings "
                + "file and writes it back when it closes, over the change.";
    }

    private static String stop(EmulatorInstance instance) throws InterruptedException {
        if (!instance.canStop()) {
            return "Close " + instance.brand() + "'s window first: BotMaker can't stop " + instance.brand()
                    + " yet, and it rewrites its settings when it closes.";
        }
        if (!EmulatorLauncher.stop(instance)) return instance.brand() + "'s stop command did not run.";
        long end = System.nanoTime() + STOP_TIMEOUT.toNanos();
        while (System.nanoTime() < end) {
            Thread.sleep(1000);
            if (EmulatorLiveness.check(instance).state() == EmulatorState.STOPPED) {
                Thread.sleep(2000); // the player writes its settings as it exits
                return null;
            }
        }
        return instance.name() + " did not stop within " + STOP_TIMEOUT.toSeconds() + " seconds: nothing was changed. "
                + "If " + instance.brand() + " runs with administrator rights, close it yourself.";
    }
}
