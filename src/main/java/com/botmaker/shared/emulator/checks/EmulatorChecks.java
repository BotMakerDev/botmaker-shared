package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.WaydroidResolution;

import java.util.List;

/**
 * The checks on an emulator BotMaker can repair: Waydroid and Android Studio's emulator. Other products, and a
 * phone over adb, have none. Reading them shells out: never on a UI thread.
 */
public final class EmulatorChecks {

    private EmulatorChecks() {}

    /**
     * @param expected the size the bot runs at, for Waydroid's size check, or {@code null} to skip it
     */
    public static List<CheckResult> run(EmulatorInstance instance, WaydroidResolution expected) {
        if (instance == null) return List.of();
        return switch (instance.platformId()) {
            case WAYDROID -> WaydroidChecks.run(instance, expected);
            case AVD -> AvdChecks.run(instance);
            default -> List.of();
        };
    }
}
