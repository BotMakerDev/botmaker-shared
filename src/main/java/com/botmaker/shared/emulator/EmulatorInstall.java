package com.botmaker.shared.emulator;

import com.botmaker.shared.Diag;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Puts a game on an emulator instance or a phone: from Google Play, where the user presses Install inside Android,
 * or from an APK file on this computer, sent over ADB. Either way the instance is started first when it is stopped
 * ({@link EmulatorReadiness#bringUp}), and its app list is read again afterwards, so the game's card shows in the
 * game dialog under its name.
 *
 * <p>Blocking, and interruptible: a UI runs it off its own thread and interrupts it to stop waiting.
 */
public final class EmulatorInstall {

    /** Google Play's package: an instance without it has no store to open. */
    static final String PLAY_STORE = "com.android.vending";
    /** How long the user has to press Install and Play has to download the game. */
    private static final Duration STORE_TIMEOUT = Duration.ofMinutes(15);
    private static final long STORE_POLL_MS = 3_000;

    private EmulatorInstall() {}

    /**
     * What an install did.
     *
     * @param ok          whether the app is on the instance now
     * @param packageName the app's package, or {@code null} when it was never known
     * @param name        what the app is called, its package when it says nothing else
     * @param message     a sentence a UI shows as it is
     */
    public record Result(boolean ok, String packageName, String name, String message) {

        static Result failed(String message) {
            return new Result(false, null, null, message);
        }
    }

    /**
     * Opens {@code packageName}'s page in Google Play on {@code instance} and waits for the user to install it
     * there: Play needs a signed-in Google account, which only the user can provide. Returns once
     * {@code pm list packages} lists it, or after fifteen minutes.
     *
     * @param progress optional narration, called on the calling thread
     */
    public static Result fromStore(EmulatorInstance instance, String packageName, String name,
                                   Consumer<String> progress) {
        if (packageName == null || !PlayStoreSearch.PACKAGE.matcher(packageName).matches()) {
            return Result.failed("\"" + packageName + "\" isn't an Android package name.");
        }
        String shown = name == null || name.isBlank() ? packageName : name;
        Optional<EmulatorInstance> ready = EmulatorReadiness.bringUp(instance, progress);
        if (Thread.currentThread().isInterrupted()) return Result.failed("Stopped waiting for " + shown + ".");
        if (ready.isEmpty()) return Result.failed(EmulatorReadiness.notReady(instance));
        EmulatorInstance live = ready.get();
        try (AdbDevice device = AdbDevice.connect(live.adb())) {
            if (listed(device, packageName)) {
                return done(live, packageName, shown, shown + " is already on " + live.name() + ".");
            }
            if (!listed(device, PLAY_STORE)) {
                return Result.failed(live.name() + " has no Google Play. Install " + shown + " from an APK file "
                        + "instead, or on an instance that has Google Play.");
            }
            Diag.log("[Emulator] opening Google Play for " + packageName + " on " + live.name());
            String opened = device.shell(storeIntent(packageName));
            if (opened != null && opened.contains("Error")) {
                return Result.failed("Google Play on " + live.name() + " wouldn't open " + shown + ": "
                        + opened.strip());
            }
            report(progress, "Google Play is open on " + live.name() + " at " + shown + ": press Install there (sign "
                    + "in with a Google account first if it asks). Waiting for it to finish…");
            long deadline = System.currentTimeMillis() + STORE_TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(STORE_POLL_MS);
                if (listed(device, packageName)) {
                    return done(live, packageName, shown, shown + " is installed on " + live.name() + ".");
                }
            }
            return Result.failed(shown + " wasn't installed on " + live.name() + " within "
                    + STORE_TIMEOUT.toMinutes() + " minutes. Install it in Google Play, then pick it in the game "
                    + "dialog.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Stopped waiting for " + shown + ".");
        } catch (RuntimeException e) {
            return Result.failed("Couldn't talk to " + live.name() + " over ADB: " + e.getMessage());
        }
    }

    /**
     * Whether {@code packageName} is installed, asked so that a failed question throws instead of answering no —
     * unlike {@link AdbDevice#isInstalled}: a dropped connection is not "no Google Play", nor fifteen minutes of
     * waiting for an app that already arrived.
     */
    private static boolean listed(AdbDevice device, String packageName) {
        return AdbDevice.parsePackageList(device.shell("pm list packages " + packageName)).contains(packageName);
    }

    /** The shell command that opens an app's page in Google Play. {@code packageName} is a checked package. */
    static String storeIntent(String packageName) {
        return "am start -a android.intent.action.VIEW -d 'market://details?id=" + packageName + "' -p " + PLAY_STORE;
    }

    /**
     * Installs the app in {@code file} ({@code .apk}, {@code .xapk} or {@code .apks}) on {@code instance}, with its
     * {@code Android/obb} data when the archive carries some, replacing an older version.
     *
     * @param progress optional narration, called on the calling thread
     */
    public static Result fromFile(EmulatorInstance instance, Path file, Consumer<String> progress) {
        ApkFile apk;
        try {
            apk = ApkFile.open(file);
        } catch (IOException e) {
            return Result.failed(e.getMessage());
        }
        try (apk) {
            Optional<EmulatorInstance> ready = EmulatorReadiness.bringUp(instance, progress);
            if (Thread.currentThread().isInterrupted()) return Result.failed("Stopped before installing " + apk.display() + ".");
            if (ready.isEmpty()) return Result.failed(EmulatorReadiness.notReady(instance));
            EmulatorInstance live = ready.get();
            try (AdbDevice device = AdbDevice.connect(live.adb())) {
                report(progress, "Installing " + apk.display() + " (" + megabytes(apk.size()) + ") on " + live.name() + "…");
                Diag.log("[Emulator] installing " + apk.packageName() + " from " + file + " on " + live.name());
                device.install(apk.apks().stream().map(Path::toFile).toList());
                // Asked, not assumed: on an Android without `cmd`, dadb runs `pm install` and never reads its answer.
                if (!listed(device, apk.packageName())) {
                    return Result.failed("Android didn't install " + apk.display() + " on " + live.name()
                            + ": it isn't listed afterwards. The file may not be for this Android version or "
                            + "processor.");
                }
                for (ApkFile.Obb obb : apk.obbs()) {
                    report(progress, "Copying " + apk.display() + "'s game data to " + live.name() + "…");
                    device.pushData(obb.local().toFile(), obb.devicePath());
                }
                return done(live, apk.packageName(), apk.display(), apk.display() + " is installed on " + live.name() + ".");
            } catch (IOException | RuntimeException e) {
                return Result.failed("Android refused " + apk.display() + " on " + live.name() + ": " + reason(e));
            }
        }
    }

    /** The install's message without dadb's wrapping, which repeats the command. */
    private static String reason(Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        return message.strip();
    }

    /** Reads the instance's apps again, so the new one is in the cache with its name and icon, and says it's done. */
    private static Result done(EmulatorInstance live, String packageName, String name, String message) {
        EmulatorAppCache cache = EmulatorAppCache.shared();
        EmulatorProbe.refresh(live, cache);
        String named = cache.packages(live).stream().filter(app -> app.packageName().equals(packageName))
                .map(EmulatorProbe.InstalledApp::display).findFirst().orElse(name);
        return new Result(true, packageName, named, message);
    }

    private static String megabytes(long bytes) {
        return Math.max(1, Math.round(bytes / 1_048_576.0)) + " MB";
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }
}
