package com.botmaker.shared.vm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A Linux guest of {@link Tart}'s, reached through {@code tart exec} and the guest agent in Cirrus Labs' images. The
 * agent runs as their user ({@value Tart#GUEST_USER}), who may use {@code sudo} without a password, so each command
 * runs through {@code sudo -n}, as root, as QEMU's agent runs them. A file goes in on the command's standard input
 * ({@code tart exec -i}), never on its command line, and is readable by root only until the caller changes that.
 * Each call is a {@code tart exec} of its own: there is no connection to hold.
 */
final class TartGuest implements GuestShell {

    private static final Duration QUICK = Duration.ofSeconds(30);
    /** {@link #readFile}'s exit for a file that isn't there, apart from a {@code tart exec} that failed. */
    private static final int NOT_THERE = 66;

    private final Tart tart;
    private final String name;

    TartGuest(Tart tart, String name) {
        this.tart = tart;
        this.name = name;
    }

    static TartGuest of(VmRecord vm) throws IOException {
        return new TartGuest(Tart.required(), vm.name());
    }

    /** {@code tart exec}'s arguments that run {@code command} as root in {@code name}; with {@code input}, {@code -i}. */
    static List<String> execArguments(String name, boolean input, List<String> command) {
        List<String> args = new ArrayList<>(List.of("exec"));
        if (input) args.add("-i");
        args.add(name);
        args.addAll(List.of("sudo", "-n", "--"));
        args.addAll(command);
        return args;
    }

    @Override
    public boolean fileExists(String path) throws IOException, InterruptedException {
        // An answer either way: a failed exec (no agent yet) is neither, and says why.
        Done done = call(null, QUICK, List.of("/bin/sh", "-c", "if [ -r \"$1\" ]; then echo yes; else echo no; fi",
                "sh", path));
        String said = done.out().strip();
        if (said.equals("yes") || said.equals("no")) return said.equals("yes");
        throw new IOException("The VM didn't answer: " + done.err().strip());
    }

    @Override
    public Optional<byte[]> readFile(String path) throws IOException, InterruptedException {
        Done done = call(null, QUICK, List.of("/bin/sh", "-c", "[ -r \"$1\" ] || exit " + NOT_THERE
                + "; exec cat -- \"$1\"", "sh", path));
        if (done.exitCode() == NOT_THERE) return Optional.empty();
        if (done.exitCode() != 0) throw new IOException("The VM couldn't read " + path + ": " + done.err().strip());
        return Optional.of(done.stdout());
    }

    @Override
    public void writeFile(String path, byte[] bytes) throws IOException, InterruptedException {
        Done done = call(bytes, QUICK, List.of("/bin/sh", "-c", "umask 077 && cat > \"$1\"", "sh", path));
        if (done.exitCode() != 0) throw new IOException("The VM couldn't write " + path + ": " + done.err().strip());
    }

    @Override
    public GuestAgent.Ran run(String program, List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(program));
        command.addAll(arguments);
        Done done = call(null, timeout, command);
        return new GuestAgent.Ran(done.exitCode(), done.out() + done.err());
    }

    /** Hands {@code program} to a shell in the background, so the {@code tart exec} ends at once. */
    @Override
    public void start(String program, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("/bin/sh", "-c", "\"$@\" >/dev/null 2>&1 &", "sh", program));
        command.addAll(arguments);
        call(null, QUICK, command);
    }

    @Override
    public void close() {
        // nothing held: each call was a process of its own
    }

    private record Done(int exitCode, byte[] stdout, byte[] stderr) {

        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String err() {
            return new String(stderr, StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code tart exec} of {@code command}, its standard input {@code input} (none when {@code null}), waiting up to
     * {@code timeout}; Tart exits with the command's own code.
     */
    private Done call(byte[] input, Duration timeout, List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(tart.command(execArguments(name, input != null, command)
                .toArray(String[]::new))).start();
        CompletableFuture<byte[]> out = drain(process.getInputStream());
        CompletableFuture<byte[]> err = drain(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            if (input != null) in.write(input);
        } catch (IOException ended) {
            // it exited before reading everything: its exit code says why
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException(command.getFirst() + " was still running in the VM after " + timeout.toSeconds()
                    + " s.");
        }
        return new Done(process.exitValue(), out.join(), err.join());
    }

    private static CompletableFuture<byte[]> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return stream.readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        }, task -> Thread.ofVirtual().name("tart-exec").start(task));
    }

    @Override
    public String toString() {
        return "TartGuest[" + name + "]";
    }
}
