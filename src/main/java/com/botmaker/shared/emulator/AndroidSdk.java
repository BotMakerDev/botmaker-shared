package com.botmaker.shared.emulator;

import com.botmaker.shared.platform.Os;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Where the Android SDK and its virtual devices are on this machine: the one answer {@link AdbTools#binary()} and
 * {@link AvdPlatform} share, so the adb that runs and the emulator that starts come from one SDK.
 *
 * <p>The SDK is {@code ANDROID_HOME}, else {@code ANDROID_SDK_ROOT}, else the folder Android Studio puts it in on each
 * OS. An app started from a desktop gets no shell profile, so the variables are rarely set and the default folder is
 * the usual answer (it was looked for on macOS only until 2026-10-31).
 */
public final class AndroidSdk {

    private AndroidSdk() {}

    /** The SDK folder, if one exists. */
    public static Optional<Path> home() {
        return home(System.getenv(), Os.current(), userHome());
    }

    /** {@link #home()} from the given environment, OS and home folder: the first that names an existing folder. */
    static Optional<Path> home(Map<String, String> env, Os os, Path userHome) {
        for (String variable : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            Optional<Path> named = path(env.get(variable)).filter(Files::isDirectory);
            if (named.isPresent()) return named;
        }
        Path folder = switch (os) {
            case LINUX -> userHome.resolve("Android/Sdk");
            case MAC -> userHome.resolve("Library/Android/sdk");
            case WINDOWS -> path(env.get("LOCALAPPDATA")).orElse(userHome.resolve("AppData/Local"))
                    .resolve("Android/Sdk");
            case UNKNOWN -> null;
        };
        return folder != null && Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
    }

    /** The SDK's emulator command, if the emulator package is installed. */
    public static Optional<Path> emulator() {
        return home().flatMap(sdk -> emulatorIn(sdk, Os.current()));
    }

    /** {@code <sdk>/emulator/emulator}, {@code .exe} on Windows, when it is a file. */
    static Optional<Path> emulatorIn(Path sdk, Os os) {
        Path command = sdk.resolve("emulator").resolve(os.isWindows() ? "emulator.exe" : "emulator");
        return Files.isRegularFile(command) ? Optional.of(command) : Optional.empty();
    }

    /** The folder holding the virtual devices, as the emulator reads it. */
    public static Path avdHome() {
        return avdHome(System.getenv(), userHome());
    }

    /** {@code ANDROID_AVD_HOME}, else {@code ANDROID_USER_HOME/avd}, else {@code ~/.android/avd}. */
    static Path avdHome(Map<String, String> env, Path userHome) {
        return path(env.get("ANDROID_AVD_HOME")).or(() -> path(env.get("ANDROID_USER_HOME")).map(u -> u.resolve("avd")))
                .orElse(userHome.resolve(".android").resolve("avd"));
    }

    /** A variable's value as a path; empty when unset, blank or no path at all ({@code "C:\sdk"}, quotes included). */
    private static Optional<Path> path(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(Path.of(value.strip()));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home", ""));
    }
}
