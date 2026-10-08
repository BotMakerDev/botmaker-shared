package com.botmaker.shared.vm;

import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.shared.launch.RunState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A launch target's processes inside a Windows game VM, found and stopped by a PowerShell script run in the guest.
 * A game is every process whose program lies in its install folder, so its helper programs count and its store
 * launcher doesn't:
 * <ul>
 *   <li>Steam and Epic: the folder the guest's own launcher records ({@code appmanifest_<id>.acf} in a Steam
 *   library, a {@code .item} in Epic's manifests);</li>
 *   <li>a path: its program's folder, so a launcher that starts the game beside it and exits still counts. A
 *   folder of Windows', or one right under a drive's root, holds more than the game: there, and for a bare name,
 *   the program's file name instead (a packaged app, Notepad, runs from elsewhere than the path that started
 *   it).</li>
 * </ul>
 * A command line can't be told apart from anything else the guest runs: it is {@link RunState#UNKNOWN}.
 */
public final class GuestGame {

    /** What the script prints when it found nowhere the game is installed. */
    static final String NOT_INSTALLED = "not-installed";
    /** What the script prints before a process that outlived {@code taskkill}. */
    static final String SURVIVED = "survived";

    private GuestGame() {}

    /**
     * What the script found: the game's processes (each {@code name (pid)}), or that it couldn't tell; after a
     * stop, the ones still running.
     */
    public record Found(RunState state, List<String> processes, List<String> survived) {

        public static final Found UNKNOWN = new Found(RunState.UNKNOWN, List.of(), List.of());
    }

    /**
     * The script that lists {@code spec}'s processes in the guest, one {@code pid<TAB>name} per line. With
     * {@code stop} it ends each one's process tree first, and then lists, after {@value #SURVIVED}, the ones
     * still running. Empty for a target it can't find ({@link GuestGame}).
     */
    static Optional<String> script(LaunchSpec spec, boolean stop) {
        String token = spec.token().trim();
        String find = switch (spec.kind()) {
            case STEAM -> GuestLaunch.STEAM_ID.matcher(token).matches() ? steamFolders(token) : null;
            case EPIC -> GuestLaunch.EPIC_ID.matcher(token).matches() ? epicFolders(token) : null;
            case EXE -> programName(spec).map(name -> programFolder(token, name)).orElse(null);
            default -> null;
        };
        if (find == null) return Optional.empty();
        return Optional.of("$ErrorActionPreference = 'SilentlyContinue'\n"
                + "[Console]::OutputEncoding = [Text.Encoding]::UTF8\n"
                + "$name = $null; $dirs = @()\n"
                + find
                + "if (-not $name -and $dirs.Count -eq 0) { '" + NOT_INSTALLED + "'; exit 0 }\n"
                + "$dirs = @($dirs | ForEach-Object { $_.TrimEnd('\\') + '\\' })\n"
                + "$found = @(Get-CimInstance Win32_Process | Where-Object {\n"
                + "  $p = $_\n"
                + "  if ($name) { $p.Name -ieq $name }\n"
                + "  else { $p.ExecutablePath -and @($dirs | Where-Object {\n"
                + "    $p.ExecutablePath.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }).Count -gt 0 }\n"
                + "})\n"
                + "foreach ($p in $found) { \"$($p.ProcessId)`t$($p.Name)\" }\n"
                + (stop ? "if ($found.Count -gt 0) {\n"
                        + "  foreach ($p in $found) { & taskkill.exe /F /T /PID $p.ProcessId 2>&1 | Out-Null }\n"
                        + "  Start-Sleep -Milliseconds 500\n"
                        + "  foreach ($p in @(Get-Process -Id @($found | ForEach-Object { $_.ProcessId }))) {"
                        + " \"" + SURVIVED + "`t$($p.Id)`t$($p.ProcessName)\" }\n"
                        + "}\n" : "")
                + "exit 0\n");
    }

    /** The script's output read back; {@link Found#UNKNOWN} when the game isn't installed where its launcher says. */
    static Found parse(String output) {
        List<String> processes = new ArrayList<>();
        List<String> survived = new ArrayList<>();
        for (String line : output.lines().map(String::strip).toList()) {
            if (line.equals(NOT_INSTALLED)) return Found.UNKNOWN;
            String[] parts = line.split("\t");
            if (parts.length == 3 && parts[0].equals(SURVIVED) && isPid(parts[1])) {
                survived.add(parts[2] + " (" + parts[1] + ")");
            } else if (parts.length == 2 && isPid(parts[0])) {
                processes.add(parts[1] + " (" + parts[0] + ")");
            }
        }
        return new Found(processes.isEmpty() ? RunState.STOPPED : RunState.RUNNING, List.copyOf(processes),
                List.copyOf(survived));
    }

    private static boolean isPid(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    /**
     * {@code spec}'s program's file name, {@code .exe} added when it has none as {@code start} adds it; empty for
     * a file that isn't a program (a shortcut, a script), whose process is something else.
     */
    static Optional<String> programName(LaunchSpec spec) {
        String name = spec.fileName().strip();
        if (name.isEmpty() || name.contains("\\") || name.contains("/") || name.contains("%")) return Optional.empty();
        if (!name.contains(".")) return Optional.of(name + ".exe");
        return name.toLowerCase(Locale.ROOT).endsWith(".exe") ? Optional.of(name) : Optional.empty();
    }

    /** {@code path}'s program's folder, where it is the game's own ({@link GuestGame}); else its {@code name}. */
    private static String programFolder(String path, String name) {
        return "$dir = Split-Path ([Environment]::ExpandEnvironmentVariables(" + quoted(path) + "))\n"
                + "if ($dir -and $dir.TrimEnd('\\').Split('\\').Count -gt 2"
                + " -and -not ($dir + '\\').StartsWith($env:windir + '\\', [StringComparison]::OrdinalIgnoreCase)) {"
                + " $dirs += $dir } else { $name = " + quoted(name) + " }\n";
    }

    /** Each Steam library's folder for app {@code id}: the libraries beside Steam, and those its list names. */
    private static String steamFolders(String id) {
        StringBuilder steams = new StringBuilder();
        for (String exe : GuestLauncher.STEAM.executables()) {
            if (!steams.isEmpty()) steams.append(", ");
            steams.append(quoted(exe.substring(0, exe.lastIndexOf('\\'))));
        }
        return "foreach ($steam in @(" + steams + ")) {\n"
                + "  $libs = @($steam)\n"
                + "  $vdf = Join-Path $steam 'steamapps\\libraryfolders.vdf'\n"
                + "  if (Test-Path $vdf) { foreach ($m in [regex]::Matches((Get-Content -Raw $vdf), '\"path\"\\s+\"([^\"]+)\"')) {"
                + " $libs += $m.Groups[1].Value.Replace('\\\\', '\\') } }\n"
                + "  foreach ($lib in $libs) {\n"
                + "    $acf = Join-Path $lib 'steamapps\\appmanifest_" + id + ".acf'\n"
                + "    if (Test-Path $acf) {\n"
                + "      $m = [regex]::Match((Get-Content -Raw $acf), '\"installdir\"\\s+\"([^\"]+)\"')\n"
                + "      if ($m.Success) { $dirs += Join-Path $lib ('steamapps\\common\\' + $m.Groups[1].Value) }\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }

    /** The install folder Epic's manifests record for app {@code id}. */
    private static String epicFolders(String id) {
        return "foreach ($f in Get-ChildItem (Join-Path " + quoted(GameCopy.EPIC_MANIFESTS) + " '*.item')) {\n"
                + "  $m = Get-Content -Raw $f.FullName | ConvertFrom-Json\n"
                + "  if ($m.AppName -eq " + quoted(id) + " -and $m.InstallLocation) { $dirs += $m.InstallLocation }\n"
                + "}\n";
    }

    /** {@code s} as a PowerShell literal string. */
    private static String quoted(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
