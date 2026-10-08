package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * A store launcher a game needs inside a game VM: the VM is a Windows of its own, so a Steam or Epic game
 * starts only once that launcher is installed and signed in there. Where it installs, and how the guest
 * installs it with nobody at the keyboard.
 */
public enum GuestLauncher {

    STEAM("steam", "Steam", List.of("C:\\Program Files (x86)\\Steam\\steam.exe", "C:\\Program Files\\Steam\\steam.exe"),
            "https://cdn.akamai.steamstatic.com/client/installer/SteamSetup.exe", "setup.exe"),
    /** Its installer of 2026-10 puts it under Program Files; older ones, under Program Files (x86). */
    EPIC("epic", "Epic Games Launcher", List.of(
            "C:\\Program Files\\Epic Games\\Launcher\\Portal\\Binaries\\Win64\\EpicGamesLauncher.exe",
            "C:\\Program Files (x86)\\Epic Games\\Launcher\\Portal\\Binaries\\Win64\\EpicGamesLauncher.exe"),
            "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/installer/download/"
                    + "EpicGamesLauncherInstaller.msi", "setup.msi"),
    UNKNOWN("unknown", "an unknown launcher", List.of(), "", "");

    private final String id;
    private final String displayName;
    private final List<String> executables;
    private final String installer;
    private final String installerFile;

    GuestLauncher(String id, String displayName, List<String> executables, String installer, String installerFile) {
        this.id = id;
        this.displayName = displayName;
        this.executables = executables;
        this.installer = installer;
        this.installerFile = installerFile;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Where it can be once installed, in the guest, likeliest first. */
    public List<String> executables() {
        return executables;
    }

    /** The launcher {@code id} names; {@link #UNKNOWN} for anything else. */
    public static GuestLauncher fromId(String id) {
        for (GuestLauncher l : values()) {
            if (l.id.equals(id)) return l;
        }
        return UNKNOWN;
    }

    /** The launchers a guest can be given. */
    public static List<GuestLauncher> installable() {
        return List.of(STEAM, EPIC);
    }

    /** The launcher {@code spec} needs in the guest, or {@link #UNKNOWN} when it needs none (a path, a command). */
    public static GuestLauncher of(LaunchSpec spec) {
        return spec.kind() == LaunchKind.STEAM ? STEAM : spec.kind() == LaunchKind.EPIC ? EPIC : UNKNOWN;
    }

    /**
     * The PowerShell that downloads the installer and runs it silently, waiting for it; it exits with the
     * installer's code. As {@code -EncodedCommand}'s argument (UTF-16LE, Base64), which needs no quoting on any
     * command line it crosses: the guest agent's, or {@code vmrun}'s.
     */
    String installScript() {
        String run = this == EPIC
                ? "Start-Process msiexec.exe -ArgumentList '/i',('\"' + $f + '\"'),'/qn','/norestart' -Wait -PassThru"
                : "Start-Process $f -ArgumentList '/S' -Wait -PassThru";
        return "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'\n"
                + "$f = Join-Path $env:TEMP 'botmaker-" + id + "-" + installerFile + "'\n"
                + "Invoke-WebRequest -UseBasicParsing -Uri '" + installer + "' -OutFile $f\n"
                + "$p = " + run + "\n"
                + "Remove-Item $f -ErrorAction SilentlyContinue\n"
                + "exit $p.ExitCode\n";
    }

    /** {@link #installScript()} as {@code powershell.exe -EncodedCommand} takes it. */
    String encodedInstallScript() {
        return Base64.getEncoder().encodeToString(installScript().getBytes(StandardCharsets.UTF_16LE));
    }
}
