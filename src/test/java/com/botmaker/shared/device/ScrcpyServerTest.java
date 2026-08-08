package com.botmaker.shared.device;

import com.botmaker.shared.tools.ManagedTools;
import com.botmaker.shared.tools.UserDirs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locating a server and reading its version — the "is the fast path available at all" question, which must be
 * answerable on a machine that has no scrcpy (this one, and any CI box).
 *
 * <p>The version is not cosmetic: the server compares the string it is given to its own and refuses a
 * mismatch, and the wire layout genuinely differs across the 2.0 boundary. So an unknown version is treated as
 * no server at all, and that is asserted here rather than left to the run-time surprise.
 */
class ScrcpyServerTest {

    @Test
    void versionsAreParsedFromTheShapesScrcpyPrints() {
        assertEquals(new ScrcpyServer.Version(2, 7, "2.7"), ScrcpyServer.parse("scrcpy 2.7"));
        assertEquals(new ScrcpyServer.Version(3, 1, "3.1"), ScrcpyServer.parse("scrcpy 3.1 <https://…>"));
        assertEquals(new ScrcpyServer.Version(2, 0, "2.0"), ScrcpyServer.parse("scrcpy-server-v2.0"));
        assertEquals(new ScrcpyServer.Version(2, 7, "2.7.1"), ScrcpyServer.parse("2.7.1"));
    }

    /** A patch version keeps its full text, because that text is what the server is handed and compared to. */
    @Test
    void aPatchVersionKeepsItsFullText() {
        assertEquals("2.7.1", ScrcpyServer.parse("scrcpy 2.7.1").text());
    }

    @Test
    void unparseableTextIsNoVersion() {
        assertNull(ScrcpyServer.parse(null));
        assertNull(ScrcpyServer.parse(""));
        assertNull(ScrcpyServer.parse("scrcpy-server"), "a distro package's name carries no version");
        assertNull(ScrcpyServer.parse("no numbers here"));
    }

    /**
     * 2.1 is the floor, and it is the floor for a specific reason: the socket arguments changed shape at 2.0
     * and {@code INJECT_TOUCH_EVENT} gained a field at 2.1, which {@link ScrcpyControl} always writes.
     */
    @Test
    void onlyTwoPointOneAndNewerIsSupported() {
        assertTrue(ScrcpyServer.parse("2.1").supported());
        assertTrue(ScrcpyServer.parse("2.7").supported());
        assertTrue(ScrcpyServer.parse("3.0").supported());
        assertFalse(ScrcpyServer.parse("2.0").supported(), "no action_button field in the touch message");
        assertFalse(ScrcpyServer.parse("1.25").supported(), "the whole argument layout is different");
    }

    /** Comparison is major-then-minor, so 3.0 beats 2.9 rather than losing on the second number. */
    @Test
    void atLeastComparesMajorBeforeMinor() {
        assertTrue(ScrcpyServer.parse("3.0").atLeast(2, 9));
        assertFalse(ScrcpyServer.parse("2.9").atLeast(3, 0));
        assertTrue(ScrcpyServer.parse("2.1").atLeast(2, 1), "at least is inclusive");
    }

    /** Every candidate must be absolute — a relative path would resolve against the working directory. */
    @Test
    void theSearchPathIsAbsoluteAndNonEmpty() {
        assertFalse(ScrcpyServer.searchPath().isEmpty());
        for (File candidate : ScrcpyServer.searchPath()) {
            assertTrue(candidate.isAbsolute(), candidate + " is not absolute");
        }
    }

    /** Availability must be answerable — either way — on a machine with no scrcpy, without throwing. */
    @Test
    void availabilityIsAnsweredWithoutThrowing() {
        assertNotNull(ScrcpyServer.locate());
        assertEquals(ScrcpyServer.locate().isPresent(), ScrcpyServer.available());
    }

    /** The hint has to name the thing to install and say what still works without it. */
    @Test
    void theInstallHintNamesScrcpyAndThePathThatStillWorks() {
        String hint = ScrcpyServer.installHint();

        assertTrue(hint.contains("scrcpy"));
        assertTrue(hint.contains("ADB") || hint.contains("adb"),
                "a user needs to know the phone still works without it");
    }

    // --- the managed copy ---

    @AfterEach
    void clearOverrides() {
        System.clearProperty(UserDirs.CACHE_PROPERTY);
        System.clearProperty(ScrcpyServer.SERVER_PROPERTY);
        System.clearProperty(ScrcpyServer.VERSION_PROPERTY);
    }

    /** BotMaker's own copy is searched — and searched <em>last</em>, so a real install keeps winning. */
    @Test
    void theManagedCopyIsTheLastCandidate(@TempDir Path cache) {
        System.setProperty(UserDirs.CACHE_PROPERTY, cache.toString());
        List<File> path = ScrcpyServer.searchPath();

        assertEquals(ManagedTools.scrcpyServerPath().toFile(), path.get(path.size() - 1));
        assertTrue(path.size() > 1, "the managed copy must not be the only place looked");
    }

    /**
     * <b>The trap the managed copy opens.</b> A machine can now hold our pinned {@code scrcpy-server-v4.1}
     * <em>and</em> a client of a different version on {@code PATH} — and asking that client for "the" version
     * would answer for a file it has nothing to do with. The server compares versions by equality, so the
     * result is not a warning but a server that exits and a socket that never accepts. For our own file the
     * version is known, not detected.
     */
    @Test
    void ourOwnFileIsVersionedByThePinAndNotByWhateverClientIsInstalled(@TempDir Path cache) throws Exception {
        System.setProperty(UserDirs.CACHE_PROPERTY, cache.toString());
        Path ours = ManagedTools.scrcpyServerPath();
        Files.createDirectories(ours.getParent());
        Files.writeString(ours, "dex");

        assertEquals(ManagedTools.SCRCPY_VERSION, ScrcpyServer.findVersion(ours.toFile()).text());
    }

    /**
     * {@code ensure()} downloads only what is missing: with a server already located, it must not touch the
     * network — asserted by the cache directory staying empty, since a fetch would have to land there.
     */
    @Test
    void ensureDoesNotDownloadWhenAServerIsAlreadyThere(@TempDir Path cache, @TempDir Path elsewhere)
            throws Exception {
        Path existing = elsewhere.resolve("scrcpy-server");
        Files.writeString(existing, "dex");
        System.setProperty(UserDirs.CACHE_PROPERTY, cache.toString());
        System.setProperty(ScrcpyServer.SERVER_PROPERTY, existing.toString());
        System.setProperty(ScrcpyServer.VERSION_PROPERTY, "2.7");

        Optional<ScrcpyServer.Located> located = ScrcpyServer.ensure();

        assertTrue(located.isPresent());
        assertEquals(existing.toFile(), located.get().jar());
        assertFalse(Files.exists(ManagedTools.scrcpyServerPath()), "nothing should have been fetched");
    }
}
