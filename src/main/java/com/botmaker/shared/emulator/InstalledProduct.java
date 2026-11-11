package com.botmaker.shared.emulator;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A Windows product as its <i>Apps &amp; features</i> entry names it: the entry's key, which changes with the
 * product's edition and major version ({@code BlueStacks_nxt}, {@code BlueStacks_msi5}, {@code LDPlayer14}), its
 * name and its version.
 *
 * @param version {@code DisplayVersion}, or {@code null} when the installer wrote none
 */
public record InstalledProduct(String key, String name, String version) {

    /**
     * The product {@code instance} runs from: the entry whose folder holds the program its launch command starts.
     * Empty off Windows, without a launch command, or when no entry holds it. Never throws.
     */
    public static Optional<InstalledProduct> of(EmulatorInstance instance) {
        List<String> launch = instance == null ? List.of() : instance.launchCommand();
        if (launch.isEmpty()) return Optional.empty();
        try {
            return InstallLocator.owning(Path.of(launch.getFirst())).map(InstalledProduct::of);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Drops the entries read in the last 30 s, so the next read sees an install just made. */
    public static void forgetCached() {
        InstallLocator.forgetUninstallEntries();
    }

    static InstalledProduct of(InstallLocator.UninstallEntry entry) {
        String name = entry.displayName() == null || entry.displayName().isBlank() ? entry.key() : entry.displayName();
        String version = entry.displayVersion() == null || entry.displayVersion().isBlank() ? null
                : entry.displayVersion().trim();
        return new InstalledProduct(entry.key(), name, version);
    }
}
