package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorLiveness;
import com.botmaker.shared.emulator.EmulatorPlatform;
import com.botmaker.shared.emulator.EmulatorState;
import com.botmaker.shared.emulator.InstalledProduct;
import com.botmaker.shared.emulator.Platforms;
import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.ManagedTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Fix that installs a product's tested version ({@link TestedVersions}): every instance of it closed, the
 * vendor's installer downloaded and checked against its sha256, then run silently behind one UAC prompt. It
 * repairs an untested version and a PC with no emulator alike. The download is deleted once installed.
 *
 * <p>LDPlayer's installer, measured 2026-11-11 over the same version: 67 s, the instances' disks and settings
 * untouched, and LDPlayer's instance manager opened at its end. LDPlayer offers its updates and never installs one
 * itself, so nothing is turned off after.
 */
final class WindowsInstallers {

    /** A silent install of a 1 GB package on a slow disk, the UAC prompt's wait included. */
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(20);

    private WindowsInstallers() {}

    /**
     * Installs {@code tested} on {@code instance}'s PC (the instance is {@code null} when none is), then reads
     * {@code recheck}.
     */
    static Fix install(TestedVersions.Tested tested, EmulatorInstance instance, Supplier<CheckResult> recheck) {
        TestedVersions.Installer installer = tested.installer();
        Path file = ManagedTools.directory().resolve("installers").resolve(installer.fileName());
        String name = tested.product() + " " + tested.version();
        return new Fix(instance == null ? EmulatorCheck.EMULATOR_INSTALLED : EmulatorCheck.EMULATOR_VERSION, List.of(
                new Fix.AsUser("Checking " + tested.product() + " is closed", words -> running(tested, instance)),
                new Fix.AsUser("Downloading " + name, words -> Downloads.fetch(installer.remote(), file,
                        percent(words, name)) ? null : "The download of " + name + " failed or did not match its "
                        + "checksum: nothing was installed."),
                new Fix.AsUser("Installing " + name, words -> {
                    words.accept("Installing " + name + ": accept Windows' permission prompt");
                    ElevatedCopy.Ran ran = ElevatedCopy.runElevated(file, installer.remote().hex(),
                            installer.silent(), INSTALL_TIMEOUT);
                    InstalledProduct.forgetCached();
                    if (!ran.finished()) return "The installer did not finish within "
                            + INSTALL_TIMEOUT.toMinutes() + " minutes.";
                    if (ran.exit() == ElevatedCopy.CHANGED_SINCE_DOWNLOAD) {
                        Files.deleteIfExists(file);
                        return "The downloaded installer changed on disk before it ran, so it was not run: Fix "
                                + "downloads it again.";
                    }
                    if (ran.exit() != 0) return "The installer ended with code " + ran.exit()
                            + " (was the permission prompt declined?).";
                    Files.deleteIfExists(file);
                    return null;
                })), recheck);
    }

    /** Why the install can't start while a {@code tested} instance runs, or null. */
    private static String running(TestedVersions.Tested tested, EmulatorInstance instance) {
        List<EmulatorInstance> now = Platforms.recent(Duration.ofSeconds(2));
        List<String> up = now.stream()
                .filter(other -> other.platformId() == tested.platform())
                // an instance whose product can't be told may be this one's: it counts
                .filter(other -> instance == null || InstalledProduct.of(other).map(InstalledProduct::key)
                        .map(tested.key()::equalsIgnoreCase).orElse(true))
                .filter(other -> EmulatorLiveness.check(other, now).state() != EmulatorState.STOPPED)
                .map(EmulatorInstance::name).toList();
        return up.isEmpty() ? null : "Close " + String.join(", ", up) + " first: the installer replaces the program "
                + "it runs.";
    }

    /** Whether {@code tested}'s product is installed now. */
    static boolean installed(TestedVersions.Tested tested) {
        return Platforms.ALL.stream().filter(p -> p.id() == tested.platform())
                .anyMatch(EmulatorPlatform::isInstalled);
    }

    /** The version row read again: {@code instance}'s product as its entry says now. */
    static CheckResult versionNow(EmulatorInstance instance) {
        Optional<InstalledProduct> product = InstalledProduct.of(instance);
        return WindowsEmulatorChecks.version(product.orElse(null));
    }

    private static Downloads.Progress percent(Consumer<String> words, String name) {
        int[] last = {-1};
        return (bytes, total) -> {
            if (total <= 0) return;
            int percent = (int) (bytes * 100 / total);
            if (percent == last[0]) return;
            last[0] = percent;
            words.accept("Downloading " + name + ": " + percent + "%");
        };
    }
}
