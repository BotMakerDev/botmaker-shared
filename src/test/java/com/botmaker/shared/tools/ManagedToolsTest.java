package com.botmaker.shared.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                ManagedTools.platformTools("Windows 11"));
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
}
