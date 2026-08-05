package com.botmaker.shared.emulator;

import java.util.Optional;

/**
 * Looking up a discovered {@link EmulatorInstance} by the name a user configured it under — the one question
 * every consumer of a saved emulator reference has to answer before it can do anything else.
 *
 * <p>It lives here because it was already being answered three times: the launch stack's own private
 * {@code find} (an {@code emu-app:<pkg>@<instance>} target names its instance), Studio's capture thumbnail
 * (a saved {@code EmulatorTarget} names one), and now the pilot's route (a {@code capture.source} of
 * {@code emulator:<name>} names one). Three copies of a scan-and-match loop are three chances to disagree
 * about what a name matches — the same drift {@link EmulatorInstance#identity()} exists to prevent for cache
 * keys.
 *
 * <p><b>Names, not identities.</b> This deliberately matches on the display name, because that is what a
 * user picked and what got persisted. It is not unique in principle (multi-instance managers default several
 * instances to the same string), so this returns the <em>first</em> match — the same thing every hand-rolled
 * copy did. Use {@code identity()} anywhere the answer must be unique.
 */
public final class EmulatorInstances {

    private EmulatorInstances() {}

    /**
     * The discovered instance with this name, if any. Total: a null or blank name is empty rather than a
     * throw, since it routinely comes from an unset config key.
     *
     * <p>Runs a full {@link Platforms#discoverAll()} scan (registry reads / console-tool calls), so it is not
     * for a hot path — resolve once and hold the result.
     */
    public static Optional<EmulatorInstance> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return Platforms.discoverAll().stream().filter(i -> wanted.equals(i.name())).findFirst();
    }
}
