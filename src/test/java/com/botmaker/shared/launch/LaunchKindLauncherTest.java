package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher identity {@link LaunchKind} now owns — process names, Flatpak app id and product name — used to
 * be three tables in {@link HostLauncherProbe} plus a third copy of the app ids inline in
 * {@link LaunchCommands}. These assertions pin the invariants that made the duplication survivable, so a
 * fourth copy can't quietly reappear.
 *
 * <p>The argvs themselves and the probe's matching are covered by {@code LaunchCommandsTest} and
 * {@code HostLauncherProbeTest}; this file only checks the data on the enum.
 */
class LaunchKindLauncherTest {

    /**
     * {@code routesThroughDaemon} is derived from {@link LaunchKind#processNames()}, not declared — so "we
     * know what its daemon is called" and "it has a daemon" are one fact and cannot disagree.
     */
    @Test
    void routingThroughADaemonMeansHavingProcessNames() {
        for (LaunchKind kind : LaunchKind.values()) {
            assertEquals(!kind.processNames().isEmpty(), kind.routesThroughDaemon(), kind.name());
            assertEquals(kind.routesThroughDaemon(), HostLauncherProbe.routesThroughDaemon(kind),
                    "the probe must agree with the enum for " + kind);
        }
        assertFalse(HostLauncherProbe.routesThroughDaemon(null), "a null kind routes through nothing");
    }

    /** Exactly the three store launchers; the rest are our own child or run over ADB. */
    @Test
    void onlyTheStoreLaunchersHaveADaemon() {
        for (LaunchKind kind : LaunchKind.values()) {
            boolean store = kind == LaunchKind.STEAM || kind == LaunchKind.HEROIC || kind == LaunchKind.FAUGUS;
            assertEquals(store, kind.routesThroughDaemon(), kind.name());
        }
    }

    /**
     * A daemon kind carries a Flatpak id and a real product name; anything else carries neither, so
     * {@link LaunchCommands}' flatpak rung can never be built for a kind that has no Flatpak form.
     */
    @Test
    void flatpakIdAndProductNameTrackTheDaemon() {
        for (LaunchKind kind : LaunchKind.values()) {
            assertNotNull(kind.productName(), kind.name());
            if (kind.routesThroughDaemon()) {
                assertNotNull(kind.flatpakAppId(), kind + " needs an app id for its flatpak rung");
                assertFalse(kind.flatpakAppId().isBlank(), kind.name());
                assertFalse("the launcher".equals(kind.productName()),
                        kind + " is a named product; the generic fallback would reach a refusal message");
            } else {
                assertNull(kind.flatpakAppId(), kind + " has no launcher, so no Flatpak form");
                assertEquals("the launcher", kind.productName(), kind.name());
            }
        }
    }

    /**
     * The process names are matched against {@code RunningProbe.programNames}, which yields lowercased names,
     * so a capital here is a name that silently never matches.
     */
    @Test
    void processNamesAreLowercase() {
        for (LaunchKind kind : LaunchKind.values()) {
            for (String name : kind.processNames()) {
                assertEquals(name.toLowerCase(Locale.ROOT), name, kind + " process name " + name);
            }
        }
    }

    /**
     * The app id is stored <b>once</b>, in the canonical case {@code flatpak run} needs — that command is
     * case-sensitive, while the probe lowercases both sides at the compare site. Before this, the two copies
     * differed in case and either one looked like a typo of the other.
     */
    @Test
    void theFlatpakIdIsStoredInFlatpakRunsCanonicalCase() {
        assertEquals("com.valvesoftware.Steam", LaunchKind.STEAM.flatpakAppId());
        assertEquals("io.github.Faugus.faugus-launcher", LaunchKind.FAUGUS.flatpakAppId());
        assertEquals("com.heroicgameslauncher.hgl", LaunchKind.HEROIC.flatpakAppId());
        assertTrue(LaunchCommands.steam("570").get(1).contains(LaunchKind.STEAM.flatpakAppId()),
                "the argv must carry the id verbatim, not a re-cased copy");
    }

    /** The refusal message names the product, not the target — "close Steam", never "close Steam game". */
    @Test
    void theRefusalMessageNamesTheProduct() {
        String message = HostLauncherProbe.refusalMessage(LaunchKind.FAUGUS);
        assertTrue(message.contains("Faugus Launcher"), message);
        assertTrue(message.contains("faugus games"), message);
    }
}
