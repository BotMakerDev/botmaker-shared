package com.botmaker.shared;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code PATH} walk, now that there is one rather than three. {@code PATH} itself is not settable from a
 * JVM, so what is asserted here is the part that does not depend on this machine's: the two questions agree,
 * a nonsense name is nobody's executable, and whatever is found is a file that could actually be run.
 */
class ExecutablesTest {

    /** {@code onPath} is now {@code find}, discarded — so they must never disagree. */
    @Test
    void whetherAndWhereAgree() {
        for (String name : new String[] {"sh", "java", "definitely-not-a-real-program-9d3f"}) {
            assertEquals(Executables.find(name).isPresent(), Executables.onPath(name), name);
        }
    }

    /**
     * A directory is "executable" in the POSIX sense — it can be traversed — so the regular-file check is what
     * stops a {@code bin/adb/} from being returned as the {@code adb} to run.
     */
    @Test
    void whatIsFoundIsARunnableFile() {
        Optional<File> found = Executables.find("sh");
        if (found.isPresent()) {
            assertTrue(found.get().isFile());
            assertTrue(found.get().canExecute());
            assertTrue(found.get().isAbsolute() || found.get().getPath().contains(File.separator));
        }
    }

    @Test
    void aBlankOrMissingNameIsNobodysExecutable() {
        assertTrue(Executables.find(null).isEmpty());
        assertTrue(Executables.find("").isEmpty());
        assertTrue(Executables.find("   ").isEmpty());
        assertFalse(Executables.onPath("definitely-not-a-real-program-9d3f"));
    }

    /** An argv[0] with a separator in it is checked where it points; {@code PATH} is not consulted for it. */
    @Test
    void aPathIsCheckedWhereItPointsRatherThanOnPath() {
        assertFalse(Executables.exists("/opt/nowhere/game.x86_64"));
        assertFalse(Executables.exists(null));
        assertFalse(Executables.exists(""));
    }
}
