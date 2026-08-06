package com.botmaker.shared.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Os} replaced four private, hand-rolled {@code os.name} sniffs ({@code WindowsRegistry},
 * {@code GameLauncher}, {@code WaydroidCli}, {@code UriLauncher}) and the two direct JNA {@code Platform} users
 * ({@code NativeControllerFactory}, {@code InputListenerFactory}) with one probe. These assertions pin the
 * invariants those six sites relied on, so a fifth copy — or a per-constant argv that drops the uri — can't
 * quietly reappear.
 *
 * <p>The tests are host-independent on purpose: {@link Os#current()} is whatever this machine is, so only its
 * memoization and self-consistency are asserted. The per-constant data is checked on every constant.
 */
class OsTest {

    /** Detected once per process: the launch and discovery paths ask repeatedly and must not re-sniff. */
    @Test
    void currentIsMemoized() {
        assertSame(Os.current(), Os.current());
    }

    /**
     * The three predicates are the whole reason the call sites could drop their own helpers, so they must
     * agree with the constant: exactly one true, except {@link Os#UNKNOWN} where all three are false.
     */
    @Test
    void exactlyOnePredicateHoldsPerConstant() {
        for (Os os : Os.values()) {
            int held = (os.isWindows() ? 1 : 0) + (os.isLinux() ? 1 : 0) + (os.isMac() ? 1 : 0);
            assertEquals(os == Os.UNKNOWN ? 0 : 1, held, os.name());
        }
    }

    /** {@code displayName()} reaches users in a refusal message ("X is not yet supported"), so never blank. */
    @Test
    void everyConstantIsNamed() {
        for (Os os : Os.values()) {
            assertFalse(os.displayName().isBlank(), os.name());
        }
    }

    /**
     * Whatever the platform's opener is called, the uri must survive as the final argument — a table entry
     * that forgets it launches the opener with no target, which is the failure mode that looks like "nothing
     * happened".
     */
    @Test
    void everyOpenCommandCarriesTheUriLast() {
        String uri = "com.epicgames.launcher://apps/X?action=launch&silent=true";
        for (Os os : Os.values()) {
            List<String> argv = os.openCommand(uri);
            assertFalse(argv.isEmpty(), os.name());
            assertEquals(uri, argv.getLast(), os + " must pass the uri through unsplit");
        }
    }

    /**
     * The freedesktop default is what {@link Os#UNKNOWN} inherits — an OS we don't model is answered, not
     * thrown at, and lands on the same branch the old {@code else} did.
     */
    @Test
    void linuxAndUnknownBothUseXdgOpen() {
        assertEquals(List.of("xdg-open", "u"), Os.LINUX.openCommand("u"));
        assertEquals(List.of("xdg-open", "u"), Os.UNKNOWN.openCommand("u"));
    }

    /**
     * Windows must go through rundll32, not {@code explorer.exe}: only the former hands a scheme carrying a
     * query string to its registered handler (the Epic "opens Documents and nothing happens" bug).
     */
    @Test
    void windowsRoutesThroughTheProtocolHandler() {
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", "u"), Os.WINDOWS.openCommand("u"));
        assertEquals(List.of("open", "u"), Os.MAC.openCommand("u"));
    }

    /** The host is one of the modelled constants and answers its own predicate — no third idiom in between. */
    @Test
    void theHostAgreesWithItself() {
        Os host = Os.current();
        assertTrue(host == Os.WINDOWS ? host.isWindows() : !host.isWindows(), host.name());
        assertEquals(host == Os.LINUX, host.isLinux(), host.name());
    }
}
