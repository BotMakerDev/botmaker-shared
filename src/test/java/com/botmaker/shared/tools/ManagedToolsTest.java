package com.botmaker.shared.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pins. Nothing here touches the network — what is asserted is that the constants are self-consistent, so
 * a typo in a digest or a URL fails the build rather than a user's download.
 */
class ManagedToolsTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(UserDirs.CACHE_PROPERTY);
    }

    private static List<Downloads.Remote> allPins() {
        return List.of(ManagedTools.SCRCPY_SERVER,
                ManagedTools.platformTools("Linux"),
                ManagedTools.platformTools("Mac OS X"),
                ManagedTools.platformTools("Windows 11"),
                ManagedTools.apkeep("Linux", "amd64"),
                ManagedTools.apkeep("Linux", "aarch64"),
                ManagedTools.apkeep("Windows 11", "amd64"),
                ManagedTools.python("Windows 11", "amd64"),
                ManagedTools.python("Linux", "amd64"),
                ManagedTools.python("Linux", "aarch64"),
                ManagedTools.python("Mac OS X", "aarch64"),
                ManagedTools.python("Mac OS X", "x86_64"));
    }

    /** The Python fetched when the computer has none is CPython 3.11 or newer, one archive per OS and processor. */
    @Test
    void pythonIsPinnedPerOsAndProcessor() {
        String[] version = ManagedTools.PYTHON_VERSION.split("\\.");
        assertTrue(Integer.parseInt(version[0]) == 3 && Integer.parseInt(version[1]) >= 11, ManagedTools.PYTHON_VERSION);
        assertTrue(ManagedTools.python("Windows 11", "amd64").url().endsWith("-x86_64-pc-windows-msvc-install_only.tar.gz"));
        assertTrue(ManagedTools.python("Linux", "arm64").url().contains("/" + ManagedTools.PYTHON_BUILD + "/cpython-"
                + ManagedTools.PYTHON_VERSION + "%2B" + ManagedTools.PYTHON_BUILD + "-aarch64-unknown-linux-gnu"));
        assertTrue(ManagedTools.python("Mac OS X", "aarch64").url().contains("-aarch64-apple-darwin-"));
        assertNull(ManagedTools.python("Windows 11", "aarch64"));
        assertNull(ManagedTools.python("Linux", "riscv64"));
        assertEquals(Path.of("/h/python/python.exe"), ManagedTools.pythonIn(Path.of("/h"), true));
        assertEquals(Path.of("/h/python/bin/python3"), ManagedTools.pythonIn(Path.of("/h"), false));
    }

    /** Windows unpacks with its own tar.exe by full path: an MSYS tar on PATH reads C:\ as a host. */
    @Test
    void windowsUnpacksWithItsOwnTar() {
        List<String> windows = ManagedTools.untar(Path.of("/a.tar.gz"), Path.of("/into"), true);
        assertTrue(windows.getFirst().toLowerCase().endsWith("system32\\tar.exe"), windows.getFirst());
        assertEquals(List.of("-xzf", Path.of("/a.tar.gz").toString(), "-C", Path.of("/into").toString()),
                windows.subList(1, 5));
        assertEquals("tar", ManagedTools.untar(Path.of("/a.tar.gz"), Path.of("/into"), false).getFirst());
    }

    @Test
    void goopdlIsEveryWheelPinnedByDigestAndPipInstallsOnlyThose() {
        assertTrue(ManagedTools.GOOPDL_REQUIREMENTS.getFirst().startsWith("goopdl==" + ManagedTools.GOOPDL_VERSION
                + " "));
        for (String line : ManagedTools.GOOPDL_REQUIREMENTS) {
            assertTrue(line.matches("[A-Za-z0-9_.-]+==[0-9.]+ --hash=sha256:[0-9a-f]{64}"), line);
        }
        List<String> pip = ManagedTools.goopdlPip(Path.of("/v/bin/python"), Path.of("/v/req.txt"));
        assertTrue(pip.containsAll(List.of("--require-hashes", "--no-deps", "--only-binary=:all:")), pip.toString());
        assertEquals(Path.of("/v/bin/goopdl"), ManagedTools.goopdlIn(Path.of("/v"), false));
        assertEquals(Path.of("/v/Scripts/goopdl.exe"), ManagedTools.goopdlIn(Path.of("/v"), true));
    }

    @Test
    void apkeepIsPinnedPerOsAndProcessorAndMacHasNone() {
        assertTrue(ManagedTools.apkeep("Linux", "x86_64").url().endsWith("-x86_64-unknown-linux-gnu"));
        assertTrue(ManagedTools.apkeep("Linux", "arm64").url().endsWith("-aarch64-unknown-linux-gnu"));
        assertTrue(ManagedTools.apkeep("Windows 11", "amd64").url().endsWith(".exe"));
        assertNull(ManagedTools.apkeep("Mac OS X", "aarch64"));
        assertNull(ManagedTools.apkeep("Linux", "riscv64"));
    }

    @Test
    void everyPinIsWellFormedPinnedAndOverTls() {
        for (Downloads.Remote pin : allPins()) {
            assertNotNull(pin);
            assertTrue(pin.wellFormed(), pin.url());
            // Both of these are executed after download — one on this machine, one on the user's phone — so a
            // pin without a size or served over plain HTTP is not a pin worth having.
            assertTrue(pin.secure(), pin.url());
            assertTrue(pin.size() > 0, pin.url());
        }
    }

    @Test
    void eachOsGetsItsOwnArchiveAndAnUnknownOneGetsNone() {
        assertTrue(ManagedTools.platformTools("Linux").url().endsWith("-linux.zip"));
        assertTrue(ManagedTools.platformTools("Mac OS X").url().endsWith("-darwin.zip"));
        assertTrue(ManagedTools.platformTools("Windows 11").url().endsWith("-win.zip"));
        assertNull(ManagedTools.platformTools("Haiku"));
    }

    /**
     * The scrcpy asset keeps its versioned file name because that is where {@code ScrcpyServer.findVersion}
     * reads the version from — renaming it to a bare {@code scrcpy-server} would leave a file whose version
     * cannot be determined, which that class treats as unusable.
     */
    @Test
    void theScrcpyServerKeepsTheVersionInItsName() {
        assertTrue(ManagedTools.SCRCPY_SERVER.url().endsWith("scrcpy-server-v" + ManagedTools.SCRCPY_VERSION));
        assertEquals("scrcpy-server-v" + ManagedTools.SCRCPY_VERSION,
                ManagedTools.scrcpyServerPath().getFileName().toString());
    }

    /** Downloaded tools live under the cache, never under the config directory the saved phones are in. */
    @Test
    void toolsLiveUnderTheCacheDirectory(@TempDir Path dir) {
        System.setProperty(UserDirs.CACHE_PROPERTY, dir.toString());
        assertEquals(dir.resolve("tools"), ManagedTools.directory());
        assertTrue(ManagedTools.scrcpyServerPath().startsWith(dir));
    }

    /** Windows asks the py launcher too, last; elsewhere there is none. */
    @Test
    void windowsAsksThePyLauncherLast() {
        assertEquals(List.of("python3", "python", "py"), ManagedTools.pythonNames(true));
        assertEquals(List.of("python3", "python"), ManagedTools.pythonNames(false));
    }

    /** The interpreter is the file Python printed on its last line, not the alias it was run by. */
    @Test
    void theInterpreterIsThePathPythonPrinted(@TempDir Path dir) throws Exception {
        Path python = Files.createFile(dir.resolve("python.exe"));
        assertEquals(python, ManagedTools.interpreter("a warning\n" + python + "\r\n").orElseThrow());
        assertTrue(ManagedTools.interpreter("").isEmpty());
        assertTrue(ManagedTools.interpreter(dir.resolve("gone.exe").toString()).isEmpty());
        assertTrue(ManagedTools.interpreter("python.exe").isEmpty());
    }
}
