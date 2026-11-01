package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.PlatformId;
import com.botmaker.shared.emulator.PlayStoreSetup;
import com.botmaker.shared.emulator.WaydroidResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each fixable check's steps; building a Fix runs nothing. */
class FixesTest {

    private static final Fixes.Context CONTEXT =
            new Fixes.Context(null, new WaydroidResolution(1280, 720), words -> null);

    private static Fix fixOf(EmulatorCheck check) {
        return Fixes.of(CheckResult.fixable(check, "broken", "fix it"), CONTEXT).orElseThrow();
    }

    @Test
    void everyFixableCheckHasItsSteps() {
        assertEquals(List.of("Starting the waydroid-container service", "Starting Waydroid"),
                fixOf(EmulatorCheck.WAYDROID_RUNNING).words());
        assertEquals(List.of("Connecting adb and waiting for Android to finish starting"),
                fixOf(EmulatorCheck.ADB_BOOTED).words());
        assertEquals(List.of("Routing Android's traffic out of this machine"), fixOf(EmulatorCheck.INTERNET).words());
        assertEquals(List.of("Re-initialising Waydroid with Google Play", "Starting Waydroid",
                        "Reading the Android ID Google asks for", "Opening Google's registration page"),
                fixOf(EmulatorCheck.GOOGLE_PLAY).words());
        assertEquals(List.of("Downloading the translator", "Installing the translator"),
                fixOf(EmulatorCheck.ARM_TRANSLATOR).words());
        assertEquals(List.of("Setting Waydroid's size to 1280x720"), fixOf(EmulatorCheck.RESOLUTION).words());
    }

    @Test
    void onlyTheStepsThatNeedItAskForRoot() {
        assertTrue(fixOf(EmulatorCheck.ARM_TRANSLATOR).needsRoot());
        assertTrue(fixOf(EmulatorCheck.INTERNET).needsRoot());
        assertFalse(fixOf(EmulatorCheck.RESOLUTION).needsRoot());
        assertFalse(fixOf(EmulatorCheck.ADB_BOOTED).needsRoot());
    }

    @Test
    void aRowWithNoFixHasNone() {
        assertEquals(Optional.empty(), Fixes.of(CheckResult.ok(EmulatorCheck.INTERNET, "fine"), CONTEXT));
        assertEquals(Optional.empty(), Fixes.of(CheckResult.byHand(EmulatorCheck.WAYDROID_INSTALLED, "no", "install",
                List.of(), null), CONTEXT));
        assertEquals(Optional.empty(), Fixes.of(CheckResult.fixable(EmulatorCheck.RESOLUTION, "no", "fix"),
                new Fixes.Context(null, null, words -> null)), "no size asked for");
        assertEquals(Optional.empty(), Fixes.of(CheckResult.fixable(EmulatorCheck.AVD_MEMORY, "no", "fix"), CONTEXT),
                "no AVD to edit");
    }

    @Test
    void aTranslatorSwapIsWhatTheCheckComplainsOf() {
        String config = "[waydroid]\nbinder_protocol = aidl3\n[properties]\nro.dalvik.vm.native.bridge = libhoudini.so\n";
        assertEquals(new WaydroidChecks.TranslatorSwap(Translator.LIBNDK, Translator.LIBHOUDINI, "13"),
                WaydroidChecks.translatorSwap("vendor_id\t: AuthenticAMD", config, null));
        assertEquals(new WaydroidChecks.TranslatorSwap(Translator.LIBHOUDINI, Translator.NONE, "11"),
                WaydroidChecks.translatorSwap("vendor_id\t: GenuineIntel", "[waydroid]\n", "11"));
        assertEquals(null, WaydroidChecks.translatorSwap("vendor_id\t: AuthenticAMD", config, "12"), "no pin");
    }

    @Test
    void undoRestoresTheBackupItNames() {
        Fix undo = Fixes.undo(Optional.of(Path.of("/b/20261102-090000"))).orElseThrow();
        assertEquals(EmulatorCheck.ARM_TRANSLATOR, undo.check());
        assertTrue(undo.needsRoot());
        Fix.AsRoot step = (Fix.AsRoot) undo.steps().getFirst();
        assertTrue(step.script().get().contains("B='/b/20261102-090000'"));
        assertEquals(Optional.empty(), Fixes.undo(Optional.empty()));
    }

    @Test
    void theInternetScriptIsTheCommandsAndRefusesAnOddInterface() {
        String script = Fixes.internetScript("enp3s0");
        assertTrue(script.startsWith("set -e\n"));
        for (String command : WaydroidChecks.routeCommands("enp3s0")) assertTrue(script.contains(command + "\n"));
        assertFalse(script.contains("ufw disable || true"), "the firewall comes back only if it was on");
        assertTrue(script.contains("trap 'ufw --force enable"));
        assertTrue(script.contains("grep -qF 'oifname \"enp3s0\" masquerade' || nft add rule"), "no second rule");
        assertFalse(Fixes.internetScript(null).contains("nft"));
        assertFalse(Fixes.internetScript("x\"; rm -rf /; \"").contains("rm -rf"), "text from `ip route` in a root script");
    }

    @Test
    void thePlayScriptsReadTheIdFromWhereTheGuideSays() {
        assertTrue(Fixes.playScript().contains("waydroid init -s GAPPS -f </dev/null"));
        String id = Fixes.androidIdScript();
        assertTrue(id.contains(PlayStoreSetup.GSERVICES_DB));
        assertTrue(id.contains(PlayStoreSetup.ANDROID_ID_QUERY));
        assertTrue(id.contains("''|*[!0-9]*) ;;"), "an error line with a digit in it is no ID");
        assertTrue(PlayStoreSetup.of(PlatformId.WAYDROID, "w").text()
                .contains(PlayStoreSetup.GSERVICES_DB));
    }

    @Test
    void memoryChangesOnlyItsLine(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.ini");
        Files.writeString(config, "avd.ini.encoding=UTF-8\nhw.ramSize=2048\nhw.lcd.density=420\n");
        assertEquals(null, Fixes.setMemory(config, false));
        assertEquals("avd.ini.encoding=UTF-8\nhw.ramSize=4096\nhw.lcd.density=420\n", Files.readString(config));
    }

    @Test
    void memoryIsRefusedWhileTheEmulatorRuns(@TempDir Path folder) throws Exception {
        Path config = folder.resolve("config.ini");
        Files.writeString(config, "hw.ramSize=2048\n");
        assertNotNull(Fixes.setMemory(config, true));
        assertEquals("hw.ramSize=2048\n", Files.readString(config));
    }

    @Test
    void memoryLineFormsAndAnAbsentOne() {
        assertEquals("a=1\r\nhw.ramSize=4096\r\nb=2\r\n", AvdChecks.withMemory("a=1\r\nhw.ramSize = 4G\r\nb=2\r\n", 4096));
        assertEquals("a=1\nhw.ramSize=4096\n", AvdChecks.withMemory("a=1", 4096));
        assertEquals("hw.ramSize=4096\n", AvdChecks.withMemory("", 4096));
    }
}
