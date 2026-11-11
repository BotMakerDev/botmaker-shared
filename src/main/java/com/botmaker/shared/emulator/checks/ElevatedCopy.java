package com.botmaker.shared.emulator.checks;

import com.botmaker.shared.emulator.WindowsCommands;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Writes an emulator's settings file, through one UAC prompt when Windows refuses the user: the counterpart of
 * {@code pkexec} for a file under {@code Program Files} or {@code ProgramData}. The text goes to a file of the user's
 * first, and an elevated PowerShell copies it over the original. The one other elevated run is an emulator's
 * pinned installer ({@link #runElevated}), its sha256 checked again on the elevated side before it starts.
 *
 * <p>A file is read and written byte for byte ({@link #BYTES}): the writers change ASCII settings only, so a byte
 * in another encoding (a GBK instance name) comes back as it was.
 */
final class ElevatedCopy {

    /** One char per byte: a file read and written in it is unchanged wherever it was not edited. */
    static final Charset BYTES = StandardCharsets.ISO_8859_1;

    private static final Duration PROMPT_TIMEOUT = Duration.ofMinutes(5);

    private ElevatedCopy() {}

    /** A command's end: whether it finished in time, its exit code, and what it printed. */
    record Ran(boolean finished, int exit, String said) {}

    /** The settings file's text, byte for byte. */
    static String read(Path file) throws IOException {
        return Files.readString(file, BYTES);
    }

    /** Writes {@code text} to {@code target}; null when it is written, else why not, in the user's words. */
    static String write(Path target, String text) throws IOException, InterruptedException {
        try {
            Files.writeString(target, text, BYTES);
            return null;
        } catch (AccessDeniedException denied) {
            Path staged = Files.createTempFile("botmaker-emulator-", ".txt");
            try {
                Files.writeString(staged, text, BYTES);
                return copyElevated(staged, target);
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }

    /** Copies {@code from} over {@code to}; null when it is copied, else why not. */
    static String copy(Path from, Path to) throws IOException, InterruptedException {
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            return null;
        } catch (AccessDeniedException denied) {
            return copyElevated(from, to);
        }
    }

    /**
     * Runs {@code command} with its output in a file, so the wait times out even while the process prints nothing
     * and never exits; a process still running then is ended.
     */
    static Ran run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        File out = Files.createTempFile("botmaker-run-", ".txt").toFile();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out).start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Ran(false, -1, "");
            }
            return new Ran(true, process.exitValue(), Files.readString(out.toPath(), BYTES).trim());
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }

    private static String copyElevated(Path from, Path to) throws IOException, InterruptedException {
        Ran ran = run(command(from, to), PROMPT_TIMEOUT);
        if (!ran.finished()) return "Windows' permission prompt was not answered.";
        if (ran.exit() == 0) return null;
        return "Windows did not allow the change to " + to + (ran.said().isEmpty() ? "" : ": " + ran.said())
                + " (was the permission prompt declined?).";
    }

    /**
     * Runs {@code program} with {@code arguments} elevated, through one UAC prompt, and waits for it: an emulator's
     * installer. A prompt declined ends with a non-zero exit.
     */
    static Ran runElevated(Path program, String sha256, List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        return run(command(program, sha256, arguments), timeout);
    }

    /** The exit of {@link #runElevated} when the program's sha256 is no longer the pinned one. */
    static final int CHANGED_SINCE_DOWNLOAD = 1_000_001;

    /**
     * The PowerShell that runs {@code program} elevated and exits with its exit code. The elevated side hashes the
     * program first: the file sits in a folder the user's own processes can write, and the prompt may wait.
     */
    static List<String> command(Path program, String sha256, List<String> arguments) {
        String path = quoted(program.toString());
        String list = arguments.isEmpty() ? "" : " -ArgumentList " + String.join(",",
                arguments.stream().map(ElevatedCopy::quoted).toList());
        return elevated("if ((Get-FileHash -LiteralPath " + path + " -Algorithm SHA256).Hash -ne " + quoted(sha256)
                + ") { exit " + CHANGED_SINCE_DOWNLOAD + " }; $q = Start-Process -FilePath " + path + list
                + " -Wait -PassThru; exit $q.ExitCode");
    }

    /**
     * The PowerShell that asks for elevation and copies. The elevated copy is passed encoded, so no path is ever
     * re-quoted by a second command line; its exit code comes back through {@code -PassThru}.
     */
    static List<String> command(Path from, Path to) {
        return elevated("Copy-Item -LiteralPath " + quoted(from.toString()) + " -Destination "
                + quoted(to.toString()) + " -Force -ErrorAction Stop");
    }

    /** {@code inner}, a PowerShell command, run in an elevated PowerShell whose exit code becomes this one's. */
    private static List<String> elevated(String inner) {
        String outer = "$p = Start-Process -FilePath powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
                + "-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','" + WindowsCommands.encoded(inner)
                + "'; exit $p.ExitCode";
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", outer);
    }

    /** A PowerShell single-quoted string: nothing in it is expanded, and a quote is doubled. */
    static String quoted(String text) {
        return WindowsCommands.quoted(text);
    }
}
