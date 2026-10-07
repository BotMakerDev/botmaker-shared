package com.botmaker.shared.emulator;

import com.botmaker.shared.Spawn;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * How to add an instance to an installed emulator: its console's create command where it has one (LDPlayer,
 * MEmu, MuMu), else the product's own instance manager, where the user creates it ({@code opensManager}:
 * BlueStacks, GameLoop). {@link EmulatorPlatform#newInstance()} gives it; discovery lists the new instance on its
 * next scan.
 *
 * @param platformId   the product
 * @param command      the host command that creates the instance or opens the manager
 * @param opensManager whether the command only opens a window the user creates the instance in
 */
public record NewInstance(PlatformId platformId, List<String> command, boolean opensManager) {

    /** A console creates an instance in seconds; MEmu copies a disk image first. */
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(3);

    public NewInstance {
        command = List.copyOf(command);
    }

    /** What a button offering it says. */
    public String label() {
        return opensManager ? platformId.displayName() + " (opens its instance manager)"
                : platformId.displayName();
    }

    /**
     * Creates the instance, or opens the manager. Returns a sentence saying what happened; throws with one when
     * the console refused. Blocking for a create (minutes at most), immediate for a manager.
     */
    public String run() throws IOException {
        if (opensManager) {
            Spawn.detached(command);
            return platformId.displayName() + "'s instance manager is open: create the instance there, then come back "
                    + "here.";
        }
        int before = instances();
        Spawn.Completed done;
        try {
            done = Spawn.run(CREATE_TIMEOUT, InstallLocator.SYSTEM_CODE_PAGE, command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped before " + platformId.displayName() + " said it was done.", e);
        }
        if (done == null) {
            throw new IOException(platformId.displayName() + " took over " + CREATE_TIMEOUT.toMinutes()
                    + " minutes to create the instance; it may still appear.");
        }
        // One more instance is the answer: LDPlayer 14's `ldconsole add` exits 1 after creating one.
        if (before >= 0 && instances() > before) return "A new " + platformId.displayName() + " instance was created.";
        if (!done.ok()) {
            String said = done.output().strip();
            throw new IOException(platformId.displayName() + " didn't create the instance"
                    + (said.isEmpty() ? " (exit " + done.exitCode() + ")." : ": " + said));
        }
        return "A new " + platformId.displayName() + " instance was created.";
    }

    /** How many instances discovery finds for the product now, or {@code -1} when it can't say. */
    private int instances() {
        try {
            for (EmulatorPlatform platform : Platforms.ALL) {
                if (platform.id() == platformId) return platform.discover().size();
            }
        } catch (RuntimeException ignored) {
            // no count: the console's exit status decides
        }
        return -1;
    }
}
