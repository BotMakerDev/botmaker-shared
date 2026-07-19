package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Discovers <b>Gameloop</b> (Tencent's Android emulator, formerly Tencent Gaming Buddy). Unlike BlueStacks /
 * LDPlayer / MEmu / MuMu, Gameloop does not expose a per-instance ADB-port config we can parse: its engine
 * ({@code AndroidEmulator.exe}, under {@code <install>/ui/}) serves ADB on the fixed loopback port
 * <b>5555</b> for its primary instance, and the user must first turn on <em>ADB debugging</em> (Settings →
 * Advanced) for that port to accept connections. Gameloop's Multi-Instance manager is niche and its extra
 * instances' ports are undocumented, so discovery is deliberately limited to the single primary instance —
 * enough for the common case without fabricating an untested multi-instance port scheme.
 *
 * <p>Detection is by install directory: the registry uninstall/Tencent keys, falling back to the default
 * {@code %ProgramFiles%/TxGameAssistant/ui/AndroidEmulator*.exe} path. Best-effort and Windows-first: no
 * install found gives an empty list. (Port 5555 collides with LDPlayer instance 0; that's harmless: the two carry
 * different {@code platformId}s and a real machine rarely runs both instance-0s at once.)
 */
public final class GameloopPlatform implements EmulatorPlatform {

    public static final String PLATFORM_ID = "gameloop";
    private static final String HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final String INSTANCE_NAME = "Gameloop";

    @Override
    public String id() {
        return PLATFORM_ID;
    }

    @Override
    public String displayName() {
        return "Gameloop";
    }

    @Override
    public boolean isInstalled() {
        return installed();
    }

    @Override
    public List<EmulatorInstance> discover() {
        if (!installed()) {
            return List.of();
        }
        EmulatorInstance base = singleInstance().get(0);
        Path engine = defaultEnginePath();
        if (engine != null) {
            // No console tool: launch is just the engine exe; there's no clean CLI stop (close the window).
            base = base.withCommands(List.of(engine.toString()), List.of());
        }
        return List.of(base);
    }

    /** The primary Gameloop instance (fixed loopback:5555). Pure + package-private so it's unit-testable. */
    static List<EmulatorInstance> singleInstance() {
        return List.of(new EmulatorInstance(PLATFORM_ID, INSTANCE_NAME, HOST, ADB_PORT));
    }

    /** Whether Gameloop appears installed — registry install dir, else the default engine path on disk. */
    private static boolean installed() {
        String installDir = firstNonNull(
                WindowsRegistry.read(
                        "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\GameLoop",
                        "InstallLocation"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\WOW6432Node\\Tencent\\GameLoop", "InstallPath"),
                WindowsRegistry.read("HKLM\\SOFTWARE\\Tencent\\GameLoop", "InstallPath"));
        if (installDir != null && !installDir.isBlank() && Files.isDirectory(Path.of(installDir.trim()))) {
            return true;
        }
        return defaultEnginePath() != null;
    }

    /** {@code <install>/ui/AndroidEmulator(En).exe} at the default location, or {@code null} if absent. */
    static Path defaultEnginePath() {
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles == null || programFiles.isBlank()) {
            return null;
        }
        Path ui = Path.of(programFiles, "TxGameAssistant", "ui");
        for (String exe : new String[]{"AndroidEmulator.exe", "AndroidEmulatorEn.exe"}) {
            Path candidate = ui.resolve(exe);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
