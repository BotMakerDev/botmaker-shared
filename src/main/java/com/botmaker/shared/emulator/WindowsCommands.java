package com.botmaker.shared.emulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The PowerShell commands that start and stop a Windows emulator where its product has no console that does:
 * BlueStacks' player and GameLoop. Each is a whole {@code powershell.exe -EncodedCommand} line, so no quote in a
 * path or a filter is ever re-read by Java's or Windows' argument splitting.
 *
 * <p><b>Why ShellExecute.</b> {@code ProcessBuilder} is {@code CreateProcess}, which refuses a program whose manifest
 * asks for administrator rights (error 740: MSI App Player's {@code HD-Player.exe}) instead of showing Windows'
 * permission prompt, and GameLoop's launcher started that way never raised its engine (2026-11-11).
 * {@code Start-Process} is ShellExecute: the program's own prompt appears, and it runs from its own folder.
 *
 * <p><b>A stop never asks for permission</b> (the user's call, 2026-11-12). It ends the processes with the user's own
 * rights. A player that runs with administrator rights refuses that, and stays up; the caller sees it still running
 * and says so.
 */
public final class WindowsCommands {

    private WindowsCommands() {}

    /** {@code command} started through ShellExecute from its program's folder. */
    public static List<String> shellExecute(List<String> command) {
        Path program = Path.of(command.getFirst());
        StringBuilder script = new StringBuilder("Start-Process -FilePath ").append(quoted(program.toString()));
        if (program.getParent() != null) {
            script.append(" -WorkingDirectory ").append(quoted(program.getParent().toString()));
        }
        if (command.size() > 1) {
            script.append(" -ArgumentList ").append(command.subList(1, command.size()).stream()
                    .map(arg -> quoted(arg.contains(" ") ? "\"" + arg + "\"" : arg))
                    .collect(Collectors.joining(",")));
        }
        return powershell(script.toString());
    }

    /**
     * Stops the BlueStacks instance {@code token}, shown as {@code title}: the {@code HD-Player.exe} whose command
     * line carries {@code --instance <token>}, or, as the player hides its command line from other programs
     * (2026-11-12), whose window is titled with the instance's name.
     */
    public static List<String> stopBlueStacks(Path hdPlayer, String token, String title) {
        return powershell(stopBlueStacksScript(hdPlayer, token, title));
    }

    /**
     * A player whose path it can read must be {@code hdPlayer}, so another edition's instance of the same token is
     * left alone; a player that hides its path too is matched by token or title only.
     */
    static String stopBlueStacksScript(Path hdPlayer, String token, String title) {
        String select = "Get-CimInstance Win32_Process -Filter \"Name='HD-Player.exe'\" | Where-Object { "
                + "(-not $_.ExecutablePath -or $_.ExecutablePath -eq " + quoted(hdPlayer.toString()) + ") -and ("
                + "$_.CommandLine -match " + quoted("--instance\\s+\"?" + regexLiteral(token) + "\"?(\\s|$)")
                + " -or (Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue).MainWindowTitle -eq "
                + quoted(title) + ") }";
        return end(select);
    }

    /** Stops every process named in {@code executables} (GameLoop's engine and window). */
    public static List<String> stopProcesses(List<String> executables) {
        return powershell(stopProcessesScript(executables));
    }

    static String stopProcessesScript(List<String> executables) {
        String names = executables.stream().map(WindowsCommands::quoted).collect(Collectors.joining(","));
        return end("Get-CimInstance Win32_Process | Where-Object { @(" + names + ") -contains $_.Name }");
    }

    /**
     * Ends what {@code select} finds. Its window is not asked to close first: BlueStacks answers that with "Do you
     * really want to close?", which nobody is there to answer (2026-11-12).
     */
    private static String end(String select) {
        return "foreach ($q in @(" + select + ")) { "
                + "Stop-Process -Id $q.ProcessId -Force -ErrorAction SilentlyContinue }\n";
    }

    /** {@code script} as a {@code powershell.exe} command line, hidden and encoded. */
    static List<String> powershell(String script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-EncodedCommand", encoded(script));
    }

    /** {@code script} as {@code -EncodedCommand} takes it: UTF-16LE, then Base64. */
    public static String encoded(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /** {@code -EncodedCommand}'s text read back, for tests and logs. */
    static String decoded(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);
    }

    /** A PowerShell single-quoted string: nothing in it is expanded, and a quote is doubled. */
    public static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** {@code text} matched literally by a .NET regular expression. */
    private static String regexLiteral(String text) {
        return text.replaceAll("([^\\w])", "\\\\$1");
    }
}
