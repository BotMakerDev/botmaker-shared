package com.botmaker.shared.emulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers <b>Gameloop</b> (Tencent's Android emulator, formerly Tencent Gaming Buddy). Unlike BlueStacks /
 * LDPlayer / MEmu / MuMu, Gameloop does not expose a per-instance ADB-port config we can parse: its engine serves
 * ADB on the fixed loopback port <b>5555</b> for its primary instance. Gameloop's Multi-Instance manager is niche and
 * its extra instances' ports are undocumented, so discovery is deliberately limited to the single primary instance —
 * enough for the common case without fabricating an untested multi-instance port scheme.
 *
 * <p>Two layouts, each an {@link Engine}:
 * <ul>
 *   <li><b>GameLoop 7</b> (seen 2026-11-10, 7.0.19.05): {@code <install>\Application\GameLoopLauncher.exe}, which runs
 *       as the user and raises the engine ({@code GameLoop.exe}, then {@code GameLoopEmulator.exe}) through one UAC
 *       prompt; the Android image under {@code <install>\Image\<version>\system.vhd}. ADB is on with no setting.</li>
 *   <li><b>Older</b>: the engine {@code AndroidEmulator(En|Ex).exe} under {@code <install>/ui/}, ADB only once the
 *       user turns on <em>ADB debugging</em> (Settings → Advanced).</li>
 * </ul>
 * Detection is by install directory ({@link #installDirs()}); the instance exists once the engine is there.
 * Best-effort and Windows-first: no install or no engine gives an empty list. (Port 5555 is also BlueStacks' and
 * LDPlayer instance 0's; both are listed, see {@link Platforms#dedupe}.)
 */
public final class GameloopPlatform implements EmulatorPlatform {

    public static final PlatformId PLATFORM_ID = PlatformId.GAMELOOP;
    private static final String HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final String INSTANCE_NAME = "Gameloop";
    /**
     * The older engine binary, in the order to try it: the English build ships the {@code En} name instead, and some
     * builds an {@code Ex} one beside it.
     */
    private static final List<String> ENGINE_EXECUTABLES = List.of("AndroidEmulator.exe", "AndroidEmulatorEn.exe",
            "AndroidEmulatorEx.exe");
    /** GameLoop 7's launcher, the one of its programs that needs no elevation, in {@code <install>\Application\}. */
    private static final String LAUNCHER_7 = "GameLoopLauncher.exe";
    /** GameLoop 7's Android engine, the process that serves ADB. */
    private static final String ENGINE_7 = "GameLoopEmulator.exe";

    /** A found engine: what starts the instance, and the processes any of which running says it has started. */
    record Engine(Path launch, List<String> processes) {}

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return !installDirs().isEmpty();
    }

    /**
     * The primary instance, once the Android engine is there. GameLoop installs as its launcher alone and downloads
     * the engine the first time a game is opened in it; until then there is nothing listening on any port, and an
     * instance row could only ever read "stopped".
     */
    @Override
    public List<EmulatorInstance> discover() {
        Engine engine = engine(installDirs());
        if (engine == null) {
            return List.of();
        }
        // No console tool: launch is the engine's own starter; there's no clean CLI stop (close the window). Any of the
        // engine's processes running says the one instance has started, none running that it is stopped.
        EmulatorState state = EmulatorState.UNKNOWN;
        for (String exe : engine.processes()) {
            Boolean running = InstallLocator.anyProcessNamed(exe);
            if (running == null) {
                state = EmulatorState.UNKNOWN;
                break;
            }
            if (running) {
                state = EmulatorState.STARTING;
                break;
            }
            state = EmulatorState.STOPPED;
        }
        return List.of(singleInstance().get(0).withCommands(List.of(engine.launch().toString()), List.of())
                .withState(state));
    }

    @Override
    public String statusNote() {
        List<Path> installs = installDirs();
        return !installs.isEmpty() && engine(installs) == null
                ? "launcher only · open a game in GameLoop once to download its Android engine"
                : null;
    }

    /**
     * GameLoop's launcher: discovery reads only its primary instance, and the launcher is where its engine is
     * downloaded the first time and its instances are managed.
     */
    @Override
    public NewInstance newInstance() {
        for (Path install : installDirs()) {
            for (Path launcher : List.of(install.resolve("Application").resolve(LAUNCHER_7),
                    install.resolve("AppMarket").resolve("AppMarket.exe"))) {
                if (Files.isRegularFile(launcher)) {
                    return new NewInstance(PLATFORM_ID, List.of(launcher.toString()), true, () -> discover().size());
                }
            }
        }
        return null;
    }

    /** The primary Gameloop instance (fixed loopback:5555). Pure + package-private so it's unit-testable. */
    static List<EmulatorInstance> singleInstance() {
        return List.of(new EmulatorInstance(PLATFORM_ID, INSTANCE_NAME, HOST, ADB_PORT));
    }

    /**
     * GameLoop's install folders: the {@code GameLoop} keys (GameLoop 7 writes {@code HKLM\SOFTWARE\Tencent\GameLoop}),
     * the {@code MobileGamePC} keys older versions write (whose {@code AppMarket\InstallPath} is the launcher one level
     * down), the uninstall entries (GameLoop 7's names {@code Application\Uninstall.exe}), then the default folders.
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
        // The uninstall entry names the launcher's folder (AppMarket, or Application); the install is its parent.
        candidates.addAll(InstallLocator.uninstallFolders(
                name -> name.equalsIgnoreCase("GameLoop") || name.equalsIgnoreCase("MobileGamePC")));
        candidates.add(InstallLocator.programFiles("Tencent", "GameLoop"));
        candidates.add(InstallLocator.programFiles("TxGameAssistant"));
        return InstallLocator.existing(candidates, GameloopPlatform::isInstall);
    }

    /** An install folder of either layout, never the launcher's own folder inside one. */
    static boolean isInstall(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (name.equalsIgnoreCase("AppMarket") || name.equalsIgnoreCase("Application")) return false;
        return Files.isRegularFile(dir.resolve("Application").resolve(LAUNCHER_7))
                || Files.isDirectory(dir.resolve("AppMarket")) || Files.isDirectory(dir.resolve("ui"));
    }

    /** The engine of the first install that has one, GameLoop 7's before the older one's, or {@code null}. */
    static Engine engine(List<Path> installs) {
        for (Path install : installs) {
            Path launcher = install.resolve("Application").resolve(LAUNCHER_7);
            if (Files.isRegularFile(launcher) && hasImage(install.resolve("Image"))) {
                return new Engine(launcher, List.of(ENGINE_7));
            }
            for (String exe : ENGINE_EXECUTABLES) {
                Path candidate = install.resolve("ui").resolve(exe);
                if (Files.isRegularFile(candidate)) {
                    return new Engine(candidate, ENGINE_EXECUTABLES);
                }
            }
        }
        return null;
    }

    /** Whether GameLoop 7 has downloaded an Android image: some {@code Image\<version>\system.vhd}. */
    private static boolean hasImage(Path images) {
        if (!Files.isDirectory(images)) return false;
        try (Stream<Path> versions = Files.list(images)) {
            return versions.anyMatch(version -> Files.isRegularFile(version.resolve("system.vhd")));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
