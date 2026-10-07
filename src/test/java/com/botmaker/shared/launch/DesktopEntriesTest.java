package com.botmaker.shared.launch;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopEntriesTest {

    @Test
    void aMenuEntryIsReadFromItsDesktopEntryGroupOnly() {
        DesktopEntries.Entry kpat = DesktopEntries.parse("org.kde.kpat", """
                [Desktop Entry]
                Type=Application
                Name=KPatience
                Name[fr]=KPatience FR
                Exec=kpat %U
                Icon=kpat
                Categories=Qt;KDE;Game;CardGame;

                [Desktop Action New]
                Name=New game
                Exec=kpat --new
                """).orElseThrow();
        assertEquals("KPatience", kpat.name(), "the untranslated name, not an action's");
        assertEquals("kpat %U", kpat.exec());
        assertTrue(kpat.game());
        assertEquals(new LaunchSpec(LaunchKind.DESKTOP, "org.kde.kpat"), kpat.spec());
    }

    @Test
    void anEntryTheMenuHidesIsSkipped() {
        assertEquals(Optional.empty(), DesktopEntries.parse("a", """
                [Desktop Entry]
                Type=Application
                Name=Helper
                Exec=helper
                NoDisplay=true
                """));
        assertEquals(Optional.empty(), DesktopEntries.parse("b", """
                [Desktop Entry]
                Type=Link
                Name=Site
                URL=https://example.org
                """));
    }

    @Test
    void aLauncherShortcutIsThatLaunchersTarget() {
        assertEquals(Optional.of(new LaunchSpec(LaunchKind.STEAM, "2379780")),
                DesktopEntries.shortcutIn("steam steam://rungameid/2379780"));
        assertEquals(Optional.of(new LaunchSpec(LaunchKind.FAUGUS, "ea-app")),
                DesktopEntries.shortcutIn("/usr/bin/faugus-launcher --game ea-app"));
        assertEquals(Optional.of(new LaunchSpec(LaunchKind.LUTRIS, "12")),
                DesktopEntries.shortcutIn("env LUTRIS_SKIP_INIT=1 lutris lutris:rungameid/12"));
        assertEquals(Optional.of(new LaunchSpec(LaunchKind.HEROIC, "Fortnite")),
                DesktopEntries.shortcutIn("xdg-open heroic://launch/legendary/Fortnite"));
        assertEquals(Optional.of(new LaunchSpec(LaunchKind.HEROIC, "Fortnite")),
                DesktopEntries.shortcutIn("xdg-open \"heroic://launch?appName=Fortnite&runner=legendary\""));
        assertEquals(Optional.of(new LaunchSpec(LaunchKind.EMULATOR_APP, "com.x.y@Waydroid")),
                DesktopEntries.shortcutIn("waydroid app launch com.x.y"));
        assertFalse(DesktopEntries.shortcutIn("firefox %u").isPresent());
    }

    @Test
    void onlyADistinctiveProgramIsEvidenceTheAppIsRunning() {
        assertEquals(java.util.List.of("kpat"), DesktopEntries.tokensOf(entry("kpat %U", "")));
        assertEquals(java.util.List.of(), DesktopEntries.tokensOf(entry("sh -c \"cd /opt/x && ./run\"", "")),
                "some sh is always running");
        assertEquals(java.util.List.of(), DesktopEntries.tokensOf(entry("java -jar /opt/x.jar", "")));
        assertEquals(java.util.List.of("org.x.Y"),
                DesktopEntries.tokensOf(entry("/usr/bin/flatpak run org.x.Y", "org.x.Y")));
    }

    private static DesktopEntries.Entry entry(String exec, String flatpakId) {
        return new DesktopEntries.Entry("id", "Files", exec, "", java.util.Set.of(), flatpakId);
    }

    @Test
    void theProgramIsFoundPastAnEnvPrefixAndQuotes() {
        assertEquals("kpat", DesktopEntries.programOf("kpat %U"));
        assertEquals("game.sh", DesktopEntries.programOf("env A=1 B=2 \"/opt/My Game/game.sh\""));
        assertEquals("", DesktopEntries.programOf("  "));
    }
}
