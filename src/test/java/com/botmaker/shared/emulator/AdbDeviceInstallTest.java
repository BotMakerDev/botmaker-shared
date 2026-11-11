package com.botmaker.shared.emulator;

import dadb.AdbShellResponse;
import dadb.Dadb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** An APK over the streamed limit is uploaded and installed from the device's copy; smaller ones stream as before. */
class AdbDeviceInstallTest {

    /** The limit the tests install under: a 10-byte file is over it. */
    private static final long LIMIT = 5;
    private static final String COPY = "/data/local/tmp/botmaker-install.apk";

    /** What the fake device was asked: {@code upload <file>}, {@code shell <command>}, {@code stream [<files>]}. */
    private final List<String> asked = new ArrayList<>();

    private AdbDevice device(Function<String, String> shell, AdbDevice.Uploader uploader) {
        Dadb dadb = (Dadb) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Dadb.class},
                (self, method, args) -> switch (method.getName()) {
                    case "shell" -> {
                        asked.add("shell " + args[0]);
                        yield new AdbShellResponse(shell.apply((String) args[0]), "", 0);
                    }
                    case "install", "installMultiple" -> {
                        asked.add(method.getName());
                        yield null;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new AdbDevice(AdbEndpoint.loopback(5555), dadb, uploader,
                apks -> asked.add("stream " + apks.stream().map(File::getName).toList()), LIMIT);
    }

    private AdbDevice device(Function<String, String> shell) {
        return device(shell, (file, remote) -> asked.add("upload " + file.getName() + " to " + remote));
    }

    /** An Android 9 device's package manager, which accepts every step. */
    private static String accepting(String command) {
        return command.startsWith("getprop ro.build.version.sdk") ? "28\n"
                : command.startsWith("pm install-create") ? "Success: created install session [42]"
                : command.startsWith("pm install") ? "Success" : "";
    }

    @Test
    void splitsUnderTheLimitStreamAsBefore(@TempDir Path dir) throws IOException {
        device(command -> "").install(List.of(apk(dir, "base.apk", 3), apk(dir, "config.apk", 3)));

        assertEquals(List.of("stream [base.apk, config.apk]"), asked);
    }

    @Test
    void aNewerAndroidStreamsASplitOverTheLimitToo(@TempDir Path dir) throws IOException {
        device(command -> command.startsWith("getprop") ? "34\n" : "")
                .install(List.of(apk(dir, "base.apk", 3), apk(dir, "assets.apk", 10)));

        assertEquals(List.of("shell getprop ro.build.version.sdk", "stream [base.apk, assets.apk]"), asked);
    }

    @Test
    void eachSplitIsUploadedWrittenAndDeletedInTurnSoOneCopyIsOnTheDevice(@TempDir Path dir) throws IOException {
        device(AdbDeviceInstallTest::accepting).install(List.of(apk(dir, "base.apk", 3), apk(dir, "assets.apk", 10)));

        assertEquals(List.of(
                "shell getprop ro.build.version.sdk",
                "shell pm install-create -r -S 13",
                "upload base.apk to " + COPY,
                "shell pm install-write -S 3 42 0 " + COPY,
                "shell rm -f " + COPY,
                "upload assets.apk to " + COPY,
                "shell pm install-write -S 10 42 1 " + COPY,
                "shell rm -f " + COPY,
                "shell pm install-commit 42"), asked);
    }

    @Test
    void aRefusedCommitSaysThePackageManagersReason(@TempDir Path dir) throws IOException {
        File assets = apk(dir, "assets.apk", 10);
        AdbDevice device = device(command -> command.startsWith("pm install-commit")
                ? "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]" : accepting(command));

        IOException refused = assertThrows(IOException.class, () -> device.install(List.of(assets)));

        assertEquals("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", refused.getMessage());
    }

    @Test
    void anUploadThatBreaksLeavesNoCopyAndNoSession(@TempDir Path dir) throws IOException {
        File assets = apk(dir, "assets.apk", 10);
        AdbDevice device = device(AdbDeviceInstallTest::accepting, (file, remote) -> {
            throw new IOException("Connection reset by peer");
        });

        assertThrows(IOException.class, () -> device.install(List.of(assets)));

        assertEquals(List.of(
                "shell getprop ro.build.version.sdk",
                "shell pm install-create -r -S 10",
                "shell rm -f " + COPY,
                "shell pm install-abandon 42"), asked);
    }

    private static File apk(Path dir, String name, int length) throws IOException {
        return Files.write(dir.resolve(name), new byte[length]).toFile();
    }
}
