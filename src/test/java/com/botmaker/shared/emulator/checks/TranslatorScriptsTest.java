package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.tools.Downloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The translator pins and the scripts that swap a translator. The scripts are run here for real, as the user,
 * with {@code sh} on temporary copies of the overlay and {@code waydroid.cfg} ({@code CFG}, {@code OVERLAY},
 * {@code BACKUPS} pointed at them, {@code WAYDROID} at {@code true}).
 */
class TranslatorScriptsTest {

    private static final String STAMP = "20261101-120000";

    private static final String CFG_HOUDINI = """
            [waydroid]
            arch = x86_64
            mount_overlays = True

            [properties]
            persist.waydroid.width = 1080
            ro.product.cpu.abilist = x86_64,x86,arm64-v8a,armeabi-v7a,armeabi
            ro.dalvik.vm.native.bridge = libhoudini.so
            ro.enable.native.bridge.exec = 1

            """;

    private static final String CFG_BARE = "[waydroid]\narch = x86_64\nmount_overlays = True\n";

    @Test
    void pinsMatchThePlansTable() {
        Downloads.Remote ndk13 = TranslatorPins.remote(Translator.LIBNDK, "13").orElseThrow();
        assertEquals("a142d1586c9eafb5edf62277110f2128bc03066179a6783bcaa33ee322e1cbd0", ndk13.hex());
        assertEquals(17827087, ndk13.size());
        assertTrue(ndk13.wellFormed() && ndk13.secure());
        assertEquals(98632332, TranslatorPins.remote(Translator.LIBHOUDINI, "11").orElseThrow().size());
        assertTrue(TranslatorPins.remote(Translator.LIBNDK, "12").isEmpty());
        assertTrue(TranslatorPins.remote(Translator.NONE, "13").isEmpty());
        assertTrue(TranslatorPins.remote(Translator.LIBNDK, null).isEmpty());
        assertEquals("vendor_google_proprietary_ndk_translation-prebuilt-68734c52556d3d7a6db34c603dd9276915c29f2f"
                + "/prebuilts", TranslatorPins.prefix(Translator.LIBNDK, "13"));
        Map<String, String> props = TranslatorPins.props(Translator.LIBNDK);
        assertEquals("libndk_translation.so", props.get(Translator.BRIDGE_PROP));
        assertEquals("0.2.3", props.get("ro.ndk_translation.version"));
        assertEquals("ro.product.cpu.abilist", props.keySet().iterator().next(), "in waydroid_script's order");
    }

    @Test
    void theInstallScriptBacksUpFirstAndNumbersItsSteps() {
        String script = TranslatorScripts.install(Translator.LIBNDK, Translator.LIBHOUDINI,
                new TranslatorPins.Archive(Path.of("/c/a.zip"), "abc", "p"), STAMP);
        assertTrue(script.contains("B=\"$BACKUPS\"/'" + STAMP + "'"));
        assertTrue(script.contains("Z='/c/a.zip'"));
        assertTrue(script.indexOf("cp -- \"$Z\"") < script.indexOf("sha256sum"), "the copy is what gets checked");
        assertTrue(script.indexOf("sha256sum") < script.indexOf("-m zipfile"), "checked before it is unpacked");
        assertTrue(script.contains("'lib/libhoudini.so'"), "the old translator's files are removed");
        assertTrue(script.contains("'lib/libndk'*"), "a pattern stays a pattern");
        assertTrue(script.contains("\"$WAYDROID\" upgrade --offline"));
        assertTrue(script.indexOf("cp -a \"$f\" \"$B/files/$f\"") < script.indexOf("rm -rf \"$f\""),
                "saved before removed");
        assertEquals(List.of(1, 2, 3, 4, 5, 6), steps(script, TranslatorScripts.INSTALL_STEPS));
        assertEquals(List.of(1, 2, 3, 4, 5), steps(TranslatorScripts.undo(Path.of("/b")),
                TranslatorScripts.UNDO_STEPS));
        assertTrue(TranslatorScripts.quote("it's").equals("'it'\\''s'"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void libndkReplacesLibhoudiniAndUndoPutsItBack(@TempDir Path root) throws Exception {
        Path overlay = root.resolve("overlay");
        Files.createDirectories(overlay.resolve("lib/arm"));
        Files.writeString(overlay.resolve("lib/libhoudini.so"), "houdini");
        Files.writeString(overlay.resolve("lib/arm/old.so"), "old arm");
        Files.writeString(overlay.resolve("unrelated"), "keep");
        Path cfg = Files.writeString(root.resolve("waydroid.cfg"), CFG_HOUDINI);
        TranslatorPins.Archive prebuilts = ndkPrebuilts(root);

        run(root, TranslatorScripts.install(Translator.LIBNDK, Translator.LIBHOUDINI, prebuilts, STAMP));

        try (var left = Files.list(root.resolve("backups"))) {
            assertEquals(List.of(STAMP), left.map(path -> path.getFileName().toString()).toList(), "no unpacked copy left");
        }
        assertFalse(Files.exists(overlay.resolve("lib/libhoudini.so")));
        assertFalse(Files.exists(overlay.resolve("lib/arm/old.so")), "a shared folder is replaced, not merged");
        assertEquals("ndk", Files.readString(overlay.resolve("lib/libndk_translation.so")));
        assertEquals("ndk arm", Files.readString(overlay.resolve("lib/arm/new.so")));
        assertEquals("keep", Files.readString(overlay.resolve("unrelated")));
        String edited = Files.readString(cfg);
        assertTrue(edited.contains("ro.dalvik.vm.native.bridge = libndk_translation.so\n"), edited);
        assertTrue(edited.contains("ro.ndk_translation.version = 0.2.3\n"), edited);
        assertTrue(edited.contains("persist.waydroid.width = 1080\n"), "other properties stay");
        assertEquals(1, edited.split("ro.dalvik.vm.native.bridge", -1).length - 1, "no key twice");
        Path backup = root.resolve("backups").resolve(STAMP);
        String manifest = Files.readString(backup.resolve("manifest"));
        assertTrue(manifest.contains("saved lib/libhoudini.so\n") && manifest.contains("saved lib/arm\n"));
        assertTrue(manifest.contains("added lib/libndk_translation.so\n"), manifest);
        assertFalse(manifest.contains("section added"));

        run(root, TranslatorScripts.undo(backup));

        assertEquals(CFG_HOUDINI, Files.readString(cfg), "the cfg byte for byte");
        assertEquals("houdini", Files.readString(overlay.resolve("lib/libhoudini.so")));
        assertEquals("old arm", Files.readString(overlay.resolve("lib/arm/old.so")));
        assertFalse(Files.exists(overlay.resolve("lib/libndk_translation.so")));
        assertFalse(Files.exists(overlay.resolve("lib/arm/new.so")));
        assertTrue(Files.isDirectory(root.resolve("backups").resolve(STAMP + "-undone")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aCfgWithoutPropertiesGainsTheSectionAndUndoRemovesIt(@TempDir Path root) throws Exception {
        Path cfg = Files.writeString(root.resolve("waydroid.cfg"), CFG_BARE);
        TranslatorPins.Archive prebuilts = ndkPrebuilts(root);

        run(root, TranslatorScripts.install(Translator.LIBNDK, Translator.NONE, prebuilts, STAMP));

        String edited = Files.readString(cfg);
        assertTrue(edited.startsWith(CFG_BARE + "[properties]\nro.product.cpu.abilist = "), edited);
        assertTrue(edited.contains("ro.dalvik.vm.native.bridge = libndk_translation.so\n"));
        Path backup = root.resolve("backups").resolve(STAMP);
        String manifest = Files.readString(backup.resolve("manifest"));
        assertTrue(manifest.startsWith("section added\n"), manifest);
        assertFalse(manifest.contains("saved "), "nothing was there to save");
        assertTrue(manifest.contains("added lib/arm\n"));

        run(root, TranslatorScripts.undo(backup));

        assertEquals(CFG_BARE, Files.readString(cfg));
        assertFalse(Files.exists(root.resolve("overlay/lib/arm")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aCrlfCfgKeepsOneSection(@TempDir Path root) throws Exception {
        Path cfg = Files.writeString(root.resolve("waydroid.cfg"), CFG_HOUDINI.replace("\n", "\r\n"));
        run(root, TranslatorScripts.install(Translator.LIBNDK, Translator.LIBHOUDINI, ndkPrebuilts(root), STAMP));
        String edited = Files.readString(cfg);
        assertEquals(1, edited.split("\\[properties]", -1).length - 1, edited);
        assertTrue(Files.readString(root.resolve("backups").resolve(STAMP).resolve("props.undo"))
                .contains("ro.dalvik.vm.native.bridge=libhoudini.so\n"), "the old value read through the CR");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void busyboxAwkEditsTheSameWay(@TempDir Path root) throws Exception {
        Path busybox = Path.of("/usr/bin/busybox");
        assumeTrue(Files.isExecutable(busybox), "no busybox here");
        Path bin = Files.createDirectories(root.resolve("bin"));
        Files.createSymbolicLink(bin.resolve("awk"), busybox);
        Path cfg = Files.writeString(root.resolve("waydroid.cfg"), CFG_HOUDINI);
        String path = "PATH=" + TranslatorScripts.quote(bin + ":" + System.getenv("PATH")) + "\n";
        TranslatorPins.Archive prebuilts = ndkPrebuilts(root);

        run(root, path + TranslatorScripts.install(Translator.LIBNDK, Translator.LIBHOUDINI, prebuilts, STAMP));
        assertEquals(1, Files.readString(cfg).split("\\[properties]", -1).length - 1);
        run(root, path + TranslatorScripts.undo(root.resolve("backups").resolve(STAMP)));
        assertEquals(CFG_HOUDINI, Files.readString(cfg));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void libhoudiniGetsItsInitFragment(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("waydroid.cfg"), CFG_BARE);
        TranslatorPins.Archive archive = archive(root, Map.of("prebuilts/lib/libhoudini.so", "houdini"));

        run(root, TranslatorScripts.install(Translator.LIBHOUDINI, Translator.NONE, archive, STAMP));

        String rc = Files.readString(root.resolve("overlay/etc/init/houdini.rc"));
        assertEquals(TranslatorPins.HOUDINI_RC, rc);
        assertTrue(rc.contains("echo ':arm_exe:M::\\\\x7f"), "two backslashes, as waydroid_script writes");
        assertTrue(Files.readString(root.resolve("backups").resolve(STAMP).resolve("manifest"))
                .contains("added etc/init/houdini.rc\n"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingFilesStopTheScriptBeforeItChangesAnything(@TempDir Path root) throws Exception {
        Path cfg = Files.writeString(root.resolve("waydroid.cfg"), CFG_HOUDINI);
        List<String> lines = new ArrayList<>();
        RootScript.Outcome outcome = RootScript.run(List.of("/bin/sh", "-s"), env(root)
                + TranslatorScripts.install(Translator.LIBNDK, Translator.LIBHOUDINI,
                new TranslatorPins.Archive(root.resolve("nope"), "abc", "prebuilts"), STAMP),
                lines::add);
        assertEquals(RootScript.Outcome.FAILED, outcome);
        assertEquals(List.of("The translator files are missing."), lines);
        assertEquals(CFG_HOUDINI, Files.readString(cfg));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void anArchiveSwappedAfterItsDownloadChangesNothing(@TempDir Path root) throws Exception {
        Path cfg = Files.writeString(root.resolve("waydroid.cfg"), CFG_HOUDINI);
        Path overlay = Files.createDirectories(root.resolve("overlay/lib"));
        Files.writeString(overlay.resolve("libhoudini.so"), "houdini");
        TranslatorPins.Archive archive = ndkPrebuilts(root);
        Files.writeString(archive.zip(), "swapped by something else this user ran");
        List<String> lines = new ArrayList<>();
        assertEquals(RootScript.Outcome.FAILED, RootScript.run(List.of("/bin/sh", "-s"), env(root)
                + TranslatorScripts.install(Translator.LIBNDK, Translator.LIBHOUDINI, archive, STAMP), lines::add));
        assertEquals(List.of("The translator archive did not match its checksum: nothing was changed."), lines);
        assertEquals(CFG_HOUDINI, Files.readString(cfg));
        assertEquals("houdini", Files.readString(overlay.resolve("libhoudini.so")));
        try (var left = Files.list(root.resolve("backups"))) {
            assertEquals(0, left.count(), "no copy left behind");
        }
    }

    /** A fake libndk archive. */
    private static TranslatorPins.Archive ndkPrebuilts(Path root) throws Exception {
        return archive(root, Map.of("prebuilts/lib/libndk_translation.so", "ndk", "prebuilts/lib/arm/new.so", "ndk arm",
                "prebuilts/bin/arm/runner", "runner"));
    }

    private static TranslatorPins.Archive archive(Path root, Map<String, String> files) throws Exception {
        Path zip = root.resolve("translator.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        return new TranslatorPins.Archive(zip, sha256, "prebuilts");
    }

    private static void run(Path root, String script) {
        List<String> lines = new ArrayList<>();
        RootScript.Outcome outcome = RootScript.run(List.of("/bin/sh", "-s"), env(root) + script, lines::add);
        assertEquals(RootScript.Outcome.DONE, outcome, String.join("\n", lines));
    }

    /** The overrides a root run never has: every path in {@code root}, and no Waydroid to call. */
    private static String env(Path root) {
        return "CFG=" + TranslatorScripts.quote(root.resolve("waydroid.cfg").toString()) + "\n"
                + "OVERLAY=" + TranslatorScripts.quote(root.resolve("overlay").toString()) + "\n"
                + "BACKUPS=" + TranslatorScripts.quote(root.resolve("backups").toString()) + "\n"
                + "WAYDROID=true\n";
    }

    private static List<Integer> steps(String script, int of) {
        List<Integer> steps = new ArrayList<>();
        Matcher m = Pattern.compile("STEP (\\d+)/(\\d+) ").matcher(script);
        while (m.find()) {
            assertEquals(of, Integer.parseInt(m.group(2)));
            steps.add(Integer.parseInt(m.group(1)));
        }
        return steps;
    }
}
