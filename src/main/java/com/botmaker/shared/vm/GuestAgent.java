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

    /** Runs the guest's {@value GuestUnattend#LAUNCH_TASK} task, which starts the launch script on the desktop. */
    public void runLaunchTask() throws IOException {
        exec("schtasks.exe", List.of("/Run", "/TN", GuestUnattend.LAUNCH_TASK));
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
