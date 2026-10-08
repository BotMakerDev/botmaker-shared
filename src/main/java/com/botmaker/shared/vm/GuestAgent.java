package com.botmaker.shared.vm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * QEMU's guest agent, reached through the VM's agent channel ({@link Qemu.Ports#agent()}): files and programs
 * inside the guest, without a network or a password. The agent comes with virtio-win's guest tools, which the
 * answer file installs at the first sign-in.
 *
 * <p>QEMU accepts the connection whether or not an agent runs in the guest, so {@link #connect} first trades a
 * random number with it ({@code guest-sync}): no answer within its timeout means no agent yet. The same trade
 * skips a reply a timed-out earlier caller left behind.
 */
public final class GuestAgent implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CHUNK = 48 * 1024;

    private final JsonChannel channel;

    private GuestAgent(JsonChannel channel) {
        this.channel = channel;
    }

    /** Connects and checks an agent answers within {@code timeoutMs}. */
    public static GuestAgent connect(int port, int timeoutMs) throws IOException {
        JsonChannel channel = JsonChannel.connect(port, timeoutMs);
        try {
            long id = RANDOM.nextInt(Integer.MAX_VALUE);
            channel.send("guest-sync", Map.of("id", id));
            while (channel.reply("guest-sync").asLong(-1) != id) {
                // a reply meant for someone before us
            }
            return new GuestAgent(channel);
        } catch (IOException e) {
            channel.close();
            throw new IOException("No guest agent answers yet.", e);
        }
    }

    /** Whether an agent answers on {@code port} within {@code timeoutMs}. */
    public static boolean answers(int port, int timeoutMs) {
        try (GuestAgent agent = connect(port, timeoutMs)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether {@code path} exists in the guest and can be read. */
    public boolean fileExists(String path) throws IOException {
        long handle;
        try {
            handle = channel.execute("guest-file-open", Map.of("path", path, "mode", "r")).asLong();
        } catch (QmpClient.Refused e) {
            return false;
        }
        channel.execute("guest-file-close", Map.of("handle", handle));
        return true;
    }

    /** {@code path}'s bytes in the guest; empty when it doesn't exist or can't be read. */
    public java.util.Optional<byte[]> readFile(String path) throws IOException {
        long handle;
        try {
            handle = channel.execute("guest-file-open", Map.of("path", path, "mode", "rb")).asLong();
        } catch (QmpClient.Refused e) {
            return java.util.Optional.empty();
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try {
            while (true) {
                JsonNode read = channel.execute("guest-file-read", Map.of("handle", handle, "count", CHUNK));
                bytes.writeBytes(Base64.getDecoder().decode(read.path("buf-b64").asText("")));
                if (read.path("eof").asBoolean(true) || read.path("count").asInt(0) == 0) break;
            }
        } finally {
            channel.execute("guest-file-close", Map.of("handle", handle));
        }
        return java.util.Optional.of(bytes.toByteArray());
    }

    /** Writes {@code bytes} to {@code path} in the guest, replacing it. */
    public void writeFile(String path, byte[] bytes) throws IOException {
        long handle = channel.execute("guest-file-open", Map.of("path", path, "mode", "wb")).asLong();
        try {
            for (int at = 0; at < bytes.length; at += CHUNK) {
                byte[] chunk = java.util.Arrays.copyOfRange(bytes, at, Math.min(bytes.length, at + CHUNK));
                channel.execute("guest-file-write", Map.of("handle", handle,
                        "buf-b64", Base64.getEncoder().encodeToString(chunk)));
            }
        } catch (IOException e) {
            try {
                channel.execute("guest-file-close", Map.of("handle", handle));
            } catch (IOException closing) {
                e.addSuppressed(closing); // the write's failure is the one to report
            }
            throw e;
        }
        channel.execute("guest-file-close", Map.of("handle", handle));
    }

    /**
     * Starts {@code program} in the guest, as the agent's own account (SYSTEM, without a desktop), and returns
     * its process id. A program for the user's desktop is started through the launch task instead.
     */
    public long exec(String program, List<String> arguments) throws IOException {
        JsonNode started = channel.execute("guest-exec", Map.of("path", program, "arg", arguments,
                "capture-output", false));
        return started.path("pid").asLong();
    }

    /** How a program run in the guest ended: its exit code, and what it printed (standard output, then error). */
    public record Ran(int exitCode, String output) {}

    /**
     * Runs {@code program} in the guest, as the agent's own account like {@link #exec}, and waits up to
     * {@code timeout} for it to end, asking {@code guest-exec-status} each second.
     *
     * @throws IOException when it is still running at {@code timeout}
     */
    public Ran run(String program, List<String> arguments, java.time.Duration timeout)
            throws IOException, InterruptedException {
        long pid = channel.execute("guest-exec", Map.of("path", program, "arg", arguments,
                "capture-output", true)).path("pid").asLong();
        long until = System.nanoTime() + timeout.toNanos();
        while (true) {
            JsonNode status = channel.execute("guest-exec-status", Map.of("pid", pid));
            if (status.path("exited").asBoolean(false)) {
                return new Ran(status.path("exitcode").asInt(-1),
                        decoded(status.path("out-data").asText("")) + decoded(status.path("err-data").asText("")));
            }
            if (System.nanoTime() > until) {
                throw new IOException(program + " was still running in the guest after " + timeout.toMinutes() + " minutes.");
            }
            Thread.sleep(1_000);
        }
    }

    /** Base64 output as text, in the console's code page as near as UTF-8 reads it. */
    private static String decoded(String base64) {
        return base64.isEmpty() ? "" : new String(Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Runs the guest's {@value GuestUnattend#LAUNCH_TASK} task, which starts the launch script on the desktop. */
    public void runLaunchTask() throws IOException {
        exec("schtasks.exe", List.of("/Run", "/TN", GuestUnattend.LAUNCH_TASK));
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
