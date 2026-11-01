package com.botmaker.shared.launch;

import com.botmaker.shared.Diag;
import com.botmaker.shared.emulator.ApkDownload;
import com.botmaker.shared.emulator.ApkFile;
import com.botmaker.shared.emulator.ApkLibrary;
import com.botmaker.shared.emulator.EmulatorInstall;
import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.EmulatorInstances;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * An Android game on an emulator instance or a phone ({@code emu-app:<package>@<instance>}). Where its file comes
 * from, first that answers:
 *
 * <ol>
 *   <li>the {@link ApkLibrary}, when it holds a version newer than the one installed;</li>
 *   <li>Google Play, when the instance has it: the user presses Install or Update there;</li>
 *   <li>APKPure through apkeep ({@link ApkDownload}), only once the user accepted APKPure's risk;</li>
 *   <li>a store page opened in the browser, the file picked up from Downloads when it lands there.</li>
 * </ol>
 *
 * <p>Play comes before apkeep because it is the game's own publisher's copy; apkeep is the way without an account
 * or a Play-less instance (Waydroid's LineageOS image). A file the user names goes in through {@link #fromFile}.
 */
public record AndroidInstall(LaunchSpec spec, boolean apkPureAcknowledged, ApkLibrary library)
        implements GameInstall {

    /** How long a store page waits for the user's download to land in Downloads. */
    private static final Duration PAGE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration PAGE_POLL = Duration.ofSeconds(3);

    AndroidInstall(LaunchSpec spec, boolean apkPureAcknowledged) {
        this(spec, apkPureAcknowledged, ApkLibrary.shared());
    }

    @Override
    public Result install(Consumer<String> progress) {
        Device device = device(progress);
        if (device.problem() != null) return device.problem();
        if (device.facts().installedVersion() >= 0) {
            return new Result(Outcome.UP_TO_DATE, name() + " is already on " + device.live().name() + ".");
        }
        return obtainAndInstall(device, progress, Outcome.INSTALLED);
    }

    @Override
    public Result update(Consumer<String> progress) {
        Device device = device(progress);
        if (device.problem() != null) return device.problem();
        long installed = device.facts().installedVersion();
        if (installed < 0) return obtainAndInstall(device, progress, Outcome.INSTALLED);
        Optional<ApkLibrary.Entry> kept = library.newest(pkg()).filter(entry -> entry.newerThan(installed));
        if (kept.isPresent()) return install(device, kept.get().file(), progress, Outcome.UPDATED);
        if (device.facts().hasPlay()) {
            return result(EmulatorInstall.openStorePage(device.live(), pkg(), name()), Outcome.WAITING_FOR_USER);
        }
        if (apkPureAcknowledged) {
            ApkDownload.Result fetched = ApkDownload.fetch(pkg(), device.facts().abi(), true, library, progress);
            if (fetched.ok()) {
                ApkLibrary.Entry entry = fetched.entry().orElseThrow();
                return entry.newerThan(installed) ? install(device, entry.file(), progress, Outcome.UPDATED)
                        : new Result(Outcome.UP_TO_DATE, name() + " is up to date on " + device.live().name()
                        + " (APKPure's newest is " + entry.version() + ").");
            }
            report(progress, fetched.message());
        }
        return fromPage(device, progress, Outcome.UPDATED);
    }

    /**
     * Adds {@code file}, which the user named, to the library and installs it: another game's file is refused and not
     * kept, an older version than the installed one refused, the same version reinstalled.
     */
    public Result fromFile(Path file, Consumer<String> progress) {
        ApkLibrary.Entry entry;
        try {
            // Read before it is kept: another game's file never enters the library, where a later install of that
            // game would take it ahead of Play and APKPure.
            try (ApkFile apk = ApkFile.open(file)) {
                if (!pkg().equals(apk.packageName())) {
                    return new Result(Outcome.FAILED, file.getFileName() + " is " + apk.packageName() + ", not "
                            + pkg() + ".");
                }
            }
            entry = library.add(file);
        } catch (IOException e) {
            return new Result(Outcome.FAILED, e.getMessage());
        }
        Device device = device(progress);
        if (device.problem() != null) return device.problem();
        Outcome outcome = device.facts().installedVersion() >= 0 ? Outcome.UPDATED : Outcome.INSTALLED;
        return install(device, entry.file(), progress, outcome);
    }

    @Override
    public String source() {
        return apkPureAcknowledged ? "the APK library, Google Play, APKPure (apkeep) or a store page"
                : "the APK library, Google Play or a store page";
    }

    /** The first way in that answers, past the library: Play, apkeep, then a store page. */
    private Result obtainAndInstall(Device device, Consumer<String> progress, Outcome outcome) {
        Optional<ApkLibrary.Entry> kept = library.newest(pkg());
        if (kept.isPresent()) return install(device, kept.get().file(), progress, outcome);
        if (device.facts().hasPlay()) {
            return result(EmulatorInstall.fromStore(device.live(), pkg(), name(), progress), outcome);
        }
        if (apkPureAcknowledged) {
            ApkDownload.Result fetched = ApkDownload.fetch(pkg(), device.facts().abi(), true, library, progress);
            if (fetched.ok()) return install(device, fetched.entry().orElseThrow().file(), progress, outcome);
            report(progress, fetched.message());
        } else {
            report(progress, device.live().name() + " has no Google Play, and downloading from APKPure isn't allowed.");
        }
        return fromPage(device, progress, outcome);
    }

    /**
     * Opens the game's store page, waits for its file to land in Downloads, and installs it. The user picks the
     * version there, as in ReVanced Manager.
     */
    private Result fromPage(Device device, Consumer<String> progress, Outcome outcome) {
        Instant start = Instant.now().minusSeconds(2);
        String page = ApkDownload.pages(pkg()).getFirst();
        if (!UriLauncher.open(page)) {
            return new Result(Outcome.NO_STORE, "No way to get " + name() + " from here: download its .apk or .xapk "
                    + "(" + String.join(" or ", ApkDownload.pages(pkg())) + "), then install it from the file.");
        }
        Path downloads = ApkLibrary.downloadsFolder();
        report(progress, "The store page for " + name() + " is open in your browser: download its .apk or .xapk. "
                + "BotMaker picks it up from " + downloads + ".");
        long deadline = System.nanoTime() + PAGE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Path> file = ApkLibrary.downloaded(downloads, pkg(), start);
            if (file.isPresent()) {
                try {
                    ApkLibrary.Entry entry = library.add(file.get());
                    long installed = device.facts().installedVersion();
                    if (installed >= 0 && !entry.newerThan(installed)) {
                        return new Result(Outcome.UP_TO_DATE, "The file you downloaded is " + name() + " "
                                + entry.version() + ", no newer than the one on " + device.live().name() + ".");
                    }
                    return install(device, entry.file(), progress, outcome);
                } catch (IOException e) {
                    return new Result(Outcome.FAILED, e.getMessage());
                }
            }
            try {
                Thread.sleep(PAGE_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Result(Outcome.WAITING_FOR_USER, "No " + name() + " file reached " + downloads + ". Once you have "
                + "it, install it from the file.");
    }

    private Result install(Device device, Path file, Consumer<String> progress, Outcome outcome) {
        return result(EmulatorInstall.fromFile(device.live(), file, progress), outcome);
    }

    private static Result result(EmulatorInstall.Result done, Outcome outcome) {
        return new Result(done.ok() ? outcome : Outcome.FAILED, done.message());
    }

    /** The instance, up, and what it says of the game; or why not. */
    private record Device(EmulatorInstance live, EmulatorInstall.DeviceFacts facts, Result problem) {}

    private Device device(Consumer<String> progress) {
        Optional<EmulatorInstance> instance = EmulatorInstances.find(spec.emulatorInstance());
        if (instance.isEmpty()) {
            return new Device(null, null, new Result(Outcome.FAILED, "No emulator or phone named \""
                    + spec.emulatorInstance() + "\" is on this computer."));
        }
        EmulatorInstall.DeviceFacts facts = EmulatorInstall.facts(instance.get(), pkg(), progress);
        if (!facts.ok()) return new Device(null, facts, new Result(Outcome.FAILED, facts.problem()));
        return new Device(facts.live(), facts, null);
    }

    private String pkg() {
        return spec.emulatorPackage();
    }

    private String name() {
        return spec.describe();
    }

    private static void report(Consumer<String> progress, String message) {
        Diag.log("[Install] " + message);
        if (progress != null) progress.accept(message);
    }
}
