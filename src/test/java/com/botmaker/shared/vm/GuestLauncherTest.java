package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which store launcher a game needs in the guest, and the script that installs it there. */
class GuestLauncherTest {

    @Test
    void aStoreGameNeedsItsLauncherAndAPathOrCommandNone() {
        assertEquals(GuestLauncher.STEAM, GuestLauncher.of(LaunchSpec.parse("steam:570")));
        assertEquals(GuestLauncher.EPIC, GuestLauncher.of(LaunchSpec.parse("epic:Fortnite")));
        assertEquals(GuestLauncher.UNKNOWN, GuestLauncher.of(LaunchSpec.parse("exe:C:\\g.exe")));
        assertEquals(GuestLauncher.EPIC, GuestLauncher.fromId("epic"));
        assertEquals(GuestLauncher.UNKNOWN, GuestLauncher.fromId("gog"));
    }

    @Test
    void theInstallScriptDownloadsAndRunsTheInstallerSilently() {
        String epic = GuestLauncher.EPIC.installScript();
        assertTrue(epic.contains("EpicGamesLauncherInstaller.msi' -OutFile $f"), epic);
        assertTrue(epic.contains("msiexec.exe -ArgumentList '/i',('\"' + $f + '\"'),'/qn','/norestart' -Wait"), epic);
        assertTrue(epic.endsWith("exit $p.ExitCode\n"), epic);

        String steam = GuestLauncher.STEAM.installScript();
        assertTrue(steam.contains("SteamSetup.exe' -OutFile $f"), steam);
        assertTrue(steam.contains("Start-Process $f -ArgumentList '/S' -Wait -PassThru"), steam);
    }
}
