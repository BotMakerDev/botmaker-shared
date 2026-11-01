package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.EmulatorInstance;
import com.botmaker.shared.emulator.WaydroidResolution;

import java.util.List;
import java.util.Optional;

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

    /**
     * The failure a launch cannot get past, if any: Waydroid missing or never set up, or its container service down.
     * A stopped session is not one, a launch starts it, and the other checks fail a game later or not at all.
     */
    public static Optional<CheckResult> stoppingLaunch(List<CheckResult> results) {
        return results.stream().filter(CheckResult::failed).filter(result -> switch (result.check()) {
            case WAYDROID_INSTALLED -> true;
            case WAYDROID_RUNNING -> !WaydroidChecks.SESSION_STOPPED.equals(result.reason());
            default -> false;
        }).findFirst();
    }
}
