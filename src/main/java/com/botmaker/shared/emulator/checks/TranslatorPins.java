package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.tools.Downloads;
import com.botmaker.shared.tools.Unzip;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Each ARM translator BotMaker can put into a Waydroid: the archive it comes from, pinned by sha256, the files
 * it copies into the system overlay and the properties it sets. The archives, file lists and properties are
 * waydroid_script's ({@code stuff/ndk.py}, {@code stuff/houdini.py}; its md5s checked equal to these sha256s on
 * 2026-11-01), so a Waydroid fixed here looks like one fixed by hand with it.
 */
public final class TranslatorPins {

    private static final String NDK_REPO = "vendor_google_proprietary_ndk_translation-prebuilt";
    private static final String HOUDINI_REPO = "vendor_intel_proprietary_houdini";

    /** One archive: the repository and commit it is GitHub's zip of, and what it must turn out to be. */
    private record Pin(String repo, String commit, long size, String sha256) {

        String url() {
            return "https://github.com/supremegamers/" + repo + "/archive/" + commit + ".zip";
        }
    }

    private static final Map<String, Pin> PINS = Map.of(
            "libndk-11", new Pin(NDK_REPO, "9324a8914b649b885dad6f2bfd14a67e5d1520bf", 14426557,
                    "87089b896ce6fed313dd5c2dd1bf22db857621c27e04471aadda69a1a2795fa1"),
            "libndk-13", new Pin(NDK_REPO, "68734c52556d3d7a6db34c603dd9276915c29f2f", 17827087,
                    "a142d1586c9eafb5edf62277110f2128bc03066179a6783bcaa33ee322e1cbd0"),
            "libhoudini-11", new Pin(HOUDINI_REPO, "81f2a51ef539a35aead396ab7fce2adf89f46e88", 98632332,
                    "c46fce463ca55eefa66965119e70d80255ca2f5f26ea31ce7ca010b7829440ed"),
            "libhoudini-13", new Pin(HOUDINI_REPO, "9e77896350caccd228b36b2e1b4a994aa4bd48da", 75100933,
                    "b09e51f6b419c73f523a79e500d7b4be56135f717e5bd1d1d7085aea656f59cd"));

    /** The ABI lists both translators declare. */
    private static final Map<String, String> ABIS = abis();

    /**
     * houdini's init fragment, which waydroid_script writes over whatever the archive has: it registers the ARM
     * binary formats with the kernel once the bridge is on.
     */
    static final String HOUDINI_RC = """

            on early-init
                mount binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc

            on property:ro.enable.native.bridge.exec=1
                exec -- /system/bin/sh -c "echo ':arm_exe:M::\\\\x7f\\\\x45\\\\x4c\\\\x46\\\\x01\\\\x01\\\\x01\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x02\\\\x00\\\\x28::/system/bin/houdini:P' > /proc/sys/fs/binfmt_misc/register"
                exec -- /system/bin/sh -c "echo ':arm_dyn:M::\\\\x7f\\\\x45\\\\x4c\\\\x46\\\\x01\\\\x01\\\\x01\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x03\\\\x00\\\\x28::/system/bin/houdini:P' >> /proc/sys/fs/binfmt_misc/register"
                exec -- /system/bin/sh -c "echo ':arm64_exe:M::\\\\x7f\\\\x45\\\\x4c\\\\x46\\\\x02\\\\x01\\\\x01\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x02\\\\x00\\\\xb7::/system/bin/houdini64:P' >> /proc/sys/fs/binfmt_misc/register"
                exec -- /system/bin/sh -c "echo ':arm64_dyn:M::\\\\x7f\\\\x45\\\\x4c\\\\x46\\\\x02\\\\x01\\\\x01\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x00\\\\x03\\\\x00\\\\xb7::/system/bin/houdini64:P' >> /proc/sys/fs/binfmt_misc/register"
            """;

    /** Where houdini's init fragment goes, relative to the overlay's {@code system}. */
    static final String HOUDINI_RC_PATH = "etc/init/houdini.rc";

    private TranslatorPins() {}

    /** The archive of {@code translator} built for Android {@code release}; empty when none is pinned. */
    public static Optional<Downloads.Remote> remote(Translator translator, String release) {
        return pin(translator, release).map(pin -> new Downloads.Remote(pin.url(), Downloads.Digest.SHA_256,
                pin.sha256(), pin.size()));
    }

    /** The folder inside the archive whose contents go to the overlay's {@code system}. */
    public static String prefix(Translator translator, String release) {
        return pin(translator, release).map(pin -> pin.repo() + "-" + pin.commit() + "/prebuilts").orElse(null);
    }

    /**
     * What {@code translator} puts under the overlay's {@code system}, as paths relative to it; a {@code *} is
     * a shell pattern. Empty for {@link Translator#NONE} and {@link Translator#UNKNOWN}.
     */
    public static List<String> files(Translator translator) {
        return switch (translator) {
            case LIBNDK -> List.of("bin/arm", "bin/arm64", "bin/ndk_translation_program_runner_binfmt_misc",
                    "bin/ndk_translation_program_runner_binfmt_misc_arm64", "etc/binfmt_misc",
                    "etc/ld.config.arm.txt", "etc/ld.config.arm64.txt", "etc/init/ndk_translation.rc", "lib/arm",
                    "lib64/arm64", "lib/libndk*", "lib64/libndk*");
            case LIBHOUDINI -> List.of("bin/arm", "bin/arm64", "bin/houdini", "bin/houdini64", "etc/binfmt_misc",
                    HOUDINI_RC_PATH, "lib/arm", "lib/libhoudini.so", "lib64/arm64", "lib64/libhoudini.so");
            case NONE, UNKNOWN -> List.of();
        };
    }

    /** The properties {@code translator} sets in {@code waydroid.cfg}'s {@code [properties]}, in order. */
    public static Map<String, String> props(Translator translator) {
        Map<String, String> props = new LinkedHashMap<>();
        switch (translator) {
            case LIBNDK -> {
                props.putAll(ABIS);
                props.put(Translator.BRIDGE_PROP, Translator.LIBNDK.library());
                props.put("ro.enable.native.bridge.exec", "1");
                props.put("ro.vendor.enable.native.bridge.exec", "1");
                props.put("ro.vendor.enable.native.bridge.exec64", "1");
                props.put("ro.ndk_translation.version", "0.2.3");
                props.put("ro.dalvik.vm.isa.arm", "x86");
                props.put("ro.dalvik.vm.isa.arm64", "x86_64");
            }
            case LIBHOUDINI -> {
                props.putAll(ABIS);
                props.put(Translator.BRIDGE_PROP, Translator.LIBHOUDINI.library());
                props.put("ro.enable.native.bridge.exec", "1");
                props.put("ro.dalvik.vm.isa.arm", "x86");
                props.put("ro.dalvik.vm.isa.arm64", "x86_64");
            }
            case NONE, UNKNOWN -> { }
        }
        return props;
    }

    /**
     * Downloads {@code translator}'s archive for {@code release} into the user's cache, checked by its sha256,
     * and unpacks it there, as the user. Returns the folder to copy from; empty when there is no pin, the
     * download failed or did not match, or the archive would not unpack.
     */
    public static Optional<Path> unpacked(Translator translator, String release, Downloads.Progress progress) {
        Optional<Downloads.Remote> remote = remote(translator, release);
        if (remote.isEmpty()) return Optional.empty();
        Path folder = UserDirs.cache().resolve("translators");
        String name = translator.id() + "-" + release.trim();
        Path zip = folder.resolve(name + ".zip");
        Path unpacked = folder.resolve(name);
        Path complete = unpacked.resolve(".complete");
        Path prebuilts = unpacked.resolve(prefix(translator, release));
        if (!Downloads.fetch(remote.get(), zip, progress)) return Optional.empty();
        if (Files.isRegularFile(complete) && Files.isDirectory(prebuilts)) return Optional.of(prebuilts);
        try {
            delete(unpacked);
            if (!Unzip.extract(zip, unpacked) || !Files.isDirectory(prebuilts)) return Optional.empty();
            Files.createFile(complete);
            return Optional.of(prebuilts);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Pin> pin(Translator translator, String release) {
        if (release == null) return Optional.empty();
        return Optional.ofNullable(PINS.get(translator.id() + "-" + release.trim()));
    }

    private static Map<String, String> abis() {
        Map<String, String> abis = new LinkedHashMap<>();
        abis.put("ro.product.cpu.abilist", "x86_64,x86,arm64-v8a,armeabi-v7a,armeabi");
        abis.put("ro.product.cpu.abilist32", "x86,armeabi-v7a,armeabi");
        abis.put("ro.product.cpu.abilist64", "x86_64,arm64-v8a");
        return abis;
    }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
