package com.botmaker.shared.emulator;

import com.botmaker.shared.platform.Os;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where the SDK is: a variable that names a folder, else Android Studio's own folder on each OS. */
class AndroidSdkTest {

    @Test
    void envWins(@TempDir Path home) throws Exception {
        Path named = Files.createDirectories(home.resolve("named"));
        Path root = Files.createDirectories(home.resolve("root"));
        Files.createDirectories(home.resolve("Android/Sdk"));

        assertEquals(Optional.of(named), AndroidSdk.home(Map.of("ANDROID_HOME", named.toString(),
                "ANDROID_SDK_ROOT", root.toString()), Os.LINUX, home));
        assertEquals(Optional.of(root), AndroidSdk.home(Map.of("ANDROID_HOME", home.resolve("gone").toString(),
                "ANDROID_SDK_ROOT", root.toString()), Os.LINUX, home), "a variable naming no folder is passed over");
    }

    @Test
    void defaultFolderPerOs(@TempDir Path home) throws Exception {
        Path linux = Files.createDirectories(home.resolve("Android/Sdk"));
        Path mac = Files.createDirectories(home.resolve("Library/Android/sdk"));
        Path local = Files.createDirectories(home.resolve("Local"));
        Path windows = Files.createDirectories(local.resolve("Android/Sdk"));

        assertEquals(Optional.of(linux), AndroidSdk.home(Map.of(), Os.LINUX, home));
        assertEquals(Optional.of(mac), AndroidSdk.home(Map.of(), Os.MAC, home));
        assertEquals(Optional.of(windows), AndroidSdk.home(Map.of("LOCALAPPDATA", local.toString()), Os.WINDOWS, home));
        assertEquals(Optional.empty(), AndroidSdk.home(Map.of(), Os.UNKNOWN, home));
    }

    /** No SDK: nothing found, and the AVD product reports nothing rather than throwing. */
    @Test
    void noSdkNoInstances(@TempDir Path home) {
        assertEquals(Optional.empty(), AndroidSdk.home(Map.of(), Os.LINUX, home));
        assertEquals(Optional.empty(), AndroidSdk.emulatorIn(home, Os.LINUX));
    }

    /** adb's lookup rests on this and must never throw: a value no path can be is passed over. */
    @Test
    void aValueThatIsNoPathIsPassedOver(@TempDir Path home) throws Exception {
        Path linux = Files.createDirectories(home.resolve("Android/Sdk"));

        assertEquals(Optional.of(linux), AndroidSdk.home(Map.of("ANDROID_HOME", "bad\0path"), Os.LINUX, home));
        assertEquals(home.resolve(".android/avd"), AndroidSdk.avdHome(Map.of("ANDROID_AVD_HOME", "bad\0path"), home));
    }

    @Test
    void emulatorAndAvdFolders(@TempDir Path home) throws Exception {
        Path emulator = Files.createDirectories(home.resolve("emulator")).resolve("emulator.exe");
        Files.writeString(emulator, "");

        assertEquals(Optional.of(emulator), AndroidSdk.emulatorIn(home, Os.WINDOWS));
        assertEquals(Optional.empty(), AndroidSdk.emulatorIn(home, Os.LINUX));
        assertEquals(home.resolve(".android/avd"), AndroidSdk.avdHome(Map.of(), home));
        assertEquals(Path.of("/u/avd"), AndroidSdk.avdHome(Map.of("ANDROID_USER_HOME", "/u"), home));
        assertEquals(Path.of("/a"), AndroidSdk.avdHome(Map.of("ANDROID_AVD_HOME", "/a", "ANDROID_USER_HOME", "/u"),
                home));
    }
}
