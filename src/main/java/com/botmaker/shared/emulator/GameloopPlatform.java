package com.botmaker.shared.emulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p>Detection is by install directory ({@link #installDirs()}); the instance exists once the engine is under its
 * {@code ui/}. Best-effort and Windows-first: no install or no engine gives an empty list. (Port 5555 is also
 * BlueStacks' and LDPlayer instance 0's; both are listed, see {@link Platforms#dedupe}.)
 */
public final class GameloopPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.GAMELOOP;
    private static final String HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final String INSTANCE_NAME = "Gameloop";
    /** The engine binary, in the order to try it: the English build ships the {@code En} name instead. */
    private static final String[] ENGINE_EXECUTABLES = {"AndroidEmulator.exe", "AndroidEmulatorEn.exe"};

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return !installDirs().isEmpty();
    }

    /**
     * The primary instance, once the Android engine is there. GameLoop installs as its launcher alone
     * ({@code AppMarket}) and downloads the engine the first time a game is opened in it; until then there is
     * nothing listening on any port, and an instance row could only ever read "stopped".
     */
    @Override
    public List<EmulatorInstance> discover() {
        Path engine = engine(installDirs());
        if (engine == null) {
            return List.of();
        }
        // No console tool: launch is just the engine exe; there's no clean CLI stop (close the window).
        return List.of(singleInstance().get(0).withCommands(List.of(engine.toString()), List.of()));
    }

    @Override
    public String statusNote() {
        List<Path> installs = installDirs();
        return !installs.isEmpty() && engine(installs) == null
                ? "launcher only · open a game in GameLoop once to download its Android engine"
                : null;
    }

    /** The primary Gameloop instance (fixed loopback:5555). Pure + package-private so it's unit-testable. */
    static List<EmulatorInstance> singleInstance() {
        return List.of(new EmulatorInstance(PLATFORM_ID, INSTANCE_NAME, HOST, ADB_PORT));
    }

    /**
     * GameLoop's install folders ({@code TxGameAssistant}): the older {@code GameLoop} keys, the {@code MobileGamePC}
     * keys current versions write (whose {@code AppMarket\InstallPath} is the launcher one level down), the
     * uninstall entries, then the default folder.
     */
    private static List<Path> installDirs() {
        List<Path> candidates = new ArrayList<>();
        for (String key : List.of("HKLM\\SOFTWARE\\WOW6432Node\\Tencent\\GameLoop", "HKLM\\SOFTWARE\\Tencent\\GameLoop")) {
            candidates.add(InstallLocator.path(WindowsRegistry.read(key, "InstallPath")));
        }
        for (String key : List.of("HKLM\\SOFTWARE\\WOW6432Node\\Tencent\\MobileGamePC\\AppMarket",
                "HKLM\\SOFTWARE\\Tencent\\MobileGamePC\\AppMarket")) {
            Path appMarket = InstallLocator.path(WindowsRegistry.read(key, "InstallPath"));
            if (appMarket != null) candidates.add(appMarket.getParent());
        }
        // The uninstall entry names the launcher's folder, AppMarket; the install is its parent, which comes next.
        candidates.addAll(InstallLocator.uninstallFolders(
                name -> name.equalsIgnoreCase("GameLoop") || name.equalsIgnoreCase("MobileGamePC")));
        candidates.add(InstallLocator.programFiles("TxGameAssistant"));
        return InstallLocator.existing(candidates, dir -> !dir.getFileName().toString().equalsIgnoreCase("AppMarket")
                && (Files.isDirectory(dir.resolve("AppMarket")) || Files.isDirectory(dir.resolve("ui"))));
    }

    /** {@code <install>/ui/AndroidEmulator(En).exe} in the first install that has it, or {@code null}. */
    static Path engine(List<Path> installs) {
        for (Path install : installs) {
            for (String exe : ENGINE_EXECUTABLES) {
                Path candidate = install.resolve("ui").resolve(exe);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
