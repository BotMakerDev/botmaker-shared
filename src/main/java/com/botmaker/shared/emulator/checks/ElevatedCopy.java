package com.botmaker.shared.emulator.checks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Writes an emulator's settings file, through one UAC prompt when Windows refuses the user: the counterpart of
 * {@code pkexec} for a file under {@code Program Files} or {@code ProgramData}. The text goes to a file of the user's
 * first, and an elevated PowerShell copies it over the original; nothing else runs elevated.
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
     * The PowerShell that asks for elevation and copies. The elevated copy is passed encoded, so no path is ever
     * re-quoted by a second command line; its exit code comes back through {@code -PassThru}.
     */
    static List<String> command(Path from, Path to) {
        String copy = "Copy-Item -LiteralPath " + quoted(from.toString()) + " -Destination " + quoted(to.toString())
                + " -Force -ErrorAction Stop";
        String encoded = Base64.getEncoder().encodeToString(copy.getBytes(StandardCharsets.UTF_16LE));
        String outer = "$p = Start-Process -FilePath powershell.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden "
                + "-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','" + encoded + "'; exit $p.ExitCode";
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", outer);
    }

    /** A PowerShell single-quoted string: nothing in it is expanded, and a quote is doubled. */
    static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}
