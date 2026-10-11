package com.botmaker.shared.vm;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Files and programs inside a Linux guest, as root, without a network or a password: QEMU's guest agent
 * ({@link GuestAgent}) or Tart's ({@link TartGuest}). What a Linux VM's displays, game copies and launcher checks
 * ask of the guest.
 */
interface GuestShell extends AutoCloseable {

    /**
     * {@code vm}'s guest: QEMU's agent must answer within {@code timeoutMs}; Tart's is asked by each call, which
     * throws when it doesn't answer.
     */
    static GuestShell open(VmRecord vm, int timeoutMs) throws IOException {
        return switch (vm.hypervisor()) {
            case QEMU -> GuestAgent.connect(vm.agentPort(), timeoutMs);
            case TART -> TartGuest.of(vm);
            case VMWARE, UNKNOWN -> throw new IOException("The game VM " + vm.name() + " isn't a Linux VM on QEMU or "
                    + "Tart, which BotMaker reaches inside.");
        };
    }

    /** Whether {@code path} exists in the guest and can be read. */
    boolean fileExists(String path) throws IOException, InterruptedException;

    /** {@code path}'s bytes in the guest; empty when it doesn't exist or can't be read. */
    Optional<byte[]> readFile(String path) throws IOException, InterruptedException;

    /** Writes {@code bytes} to {@code path} in the guest, replacing it. */
    void writeFile(String path, byte[] bytes) throws IOException, InterruptedException;

    /**
     * Runs {@code program} with {@code arguments} and waits up to {@code timeout} for it to end.
     *
     * @throws IOException when it is still running at {@code timeout}
     */
    GuestAgent.Ran run(String program, List<String> arguments, Duration timeout) throws IOException, InterruptedException;

    /** Starts {@code program} with {@code arguments} and returns without waiting for it. */
    void start(String program, List<String> arguments) throws IOException, InterruptedException;

    @Override
    void close() throws IOException;
}
