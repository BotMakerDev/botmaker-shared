package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.AndroidSdk;
import com.botmaker.shared.emulator.EmulatorInstance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The checks on an Android Studio emulator (AVD): an image with Google Play, and enough memory for a game.
 * Both read the AVD's {@code config.ini}; neither needs the AVD running.
 */
public final class AvdChecks {

    /** The memory, in MB, below which a 3D game stalls or is killed. */
    static final int MIN_RAM_MB = 4096;

    private AvdChecks() {}

    /** The checks on the AVD {@code instance} names (its id); empty when its config can't be read. */
    public static List<CheckResult> run(EmulatorInstance instance) {
        String config = WaydroidChecks.read(configIni(AndroidSdk.avdHome(), instance.name()));
        if (config == null) return List.of();
        List<CheckResult> rows = new ArrayList<>();
        rows.add(playImage(config));
        CheckResult memory = memory(config);
        if (memory != null) rows.add(memory);
        return List.copyOf(rows);
    }

    /** The AVD's {@code config.ini}: under the folder its {@code <id>.ini} points to, else {@code <id>.avd}. */
    public static Path configIni(Path avdHome, String id) {
        String pointer = WaydroidChecks.read(avdHome.resolve(id + ".ini"));
        String path = pointer == null ? null : value(pointer, "path");
        return (path == null ? avdHome.resolve(id + ".avd") : Path.of(path)).resolve("config.ini");
    }

    static CheckResult playImage(String config) {
        String enabled = value(config, "PlayStore.enabled");
        String tag = value(config, "tag.id");
        if ("true".equalsIgnoreCase(enabled) || "google_apis_playstore".equals(tag)) {
            return CheckResult.ok(EmulatorCheck.AVD_PLAY_IMAGE, "Its system image has Google Play.");
        }
        if (enabled == null && tag == null) {
            return CheckResult.info(EmulatorCheck.AVD_PLAY_IMAGE, "Can't tell whether its image has Google Play.");
        }
        return CheckResult.byHand(EmulatorCheck.AVD_PLAY_IMAGE, "Its system image has no Google Play: games "
                        + "install from files only.", "In Android Studio's Device Manager, create a device whose "
                        + "system image says \"Google Play\", and pick it as the game's emulator.", List.of(),
                null);
    }

    /** Null when {@code hw.ramSize} is not set: the emulator's own default, nothing to assert. */
    static CheckResult memory(String config) {
        int mb = megabytes(value(config, "hw.ramSize"));
        if (mb < 0) return null;
        if (mb >= MIN_RAM_MB) return CheckResult.ok(EmulatorCheck.AVD_MEMORY, mb + " MB of memory.");
        return CheckResult.fixable(EmulatorCheck.AVD_MEMORY, "Only " + mb + " MB of memory: games need "
                + MIN_RAM_MB + " MB.", "Fix sets it to " + MIN_RAM_MB + " MB while the emulator is stopped.");
    }

    /** {@code 2048}, {@code 2048M}, {@code 2048MB} or {@code 4G} in MB; -1 when absent or unreadable. */
    static int megabytes(String value) {
        if (value == null) return -1;
        String v = value.trim().toUpperCase(Locale.ROOT);
        int scale = 1;
        if (v.endsWith("GB") || v.endsWith("G")) {
            scale = 1024;
            v = v.substring(0, v.indexOf('G'));
        } else if (v.endsWith("MB") || v.endsWith("M")) {
            v = v.substring(0, v.indexOf('M'));
        }
        try {
            return Math.multiplyExact(Integer.parseInt(v.trim()), scale);
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    /** {@code key}'s value in a {@code key=value} file, trimmed; null when absent. */
    static String value(String text, String key) {
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(key)) return line.substring(eq + 1).trim();
        }
        return null;
    }
}
