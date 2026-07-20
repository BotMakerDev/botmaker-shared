package com.botmaker.shared.emulator;

import java.util.ArrayList;
import java.util.List;

/**
 * The registry of known {@link EmulatorPlatform}s and the one place to enumerate instances across all of
 * them. BlueStacks, LDPlayer, MEmu, MuMu and Gameloop all discover for real today (Gameloop is limited to
 * its single primary instance). Add a product by adding it to {@link #ALL}.
 */
public final class Platforms {

    private Platforms() {}

    /** All known platforms, real discovery first. */
    public static final List<EmulatorPlatform> ALL = List.of(
            new BlueStacksPlatform(),
            new LdPlayerPlatform(),
            new MemuPlatform(),
            new MuMuPlatform(),
            new GameloopPlatform());

    /** Every discovered instance across every platform. Never throws; empty if nothing is installed. */
    public static List<EmulatorInstance> discoverAll() {
        return discoverDetailed().instances();
    }

    /**
     * Discovery plus a per-product status line, so a UI can tell the user what it actually saw — "MuMu:
     * installed, 2 instances · BlueStacks: not installed · LDPlayer: read failed" — instead of a bare empty
     * list. Never throws; a misbehaving platform is recorded as a status with an {@code error} rather than
     * sinking the whole scan.
     */
    public static DiscoveryReport discoverDetailed() {
        List<EmulatorInstance> all = new ArrayList<>();
        List<PlatformStatus> statuses = new ArrayList<>();
        for (EmulatorPlatform platform : ALL) {
            boolean installed = false;
            try {
                installed = platform.isInstalled();
            } catch (Exception ignored) {
                // install detection is best-effort; treat a failure as "not installed" for the status
            }
            try {
                List<EmulatorInstance> found = platform.discover();
                all.addAll(found);
                statuses.add(new PlatformStatus(platform.id(), installed, found.size(), null));
            } catch (Exception e) {
                statuses.add(new PlatformStatus(platform.id(), installed, 0, e.getClass().getSimpleName()));
            }
        }
        return new DiscoveryReport(List.copyOf(all), List.copyOf(statuses));
    }

    /**
     * The outcome of a {@link #discoverDetailed()} scan: every discovered instance, plus one {@link
     * PlatformStatus} per known product describing what discovery found for it.
     */
    public record DiscoveryReport(List<EmulatorInstance> instances, List<PlatformStatus> statuses) {
        public DiscoveryReport {
            instances = List.copyOf(instances);
            statuses = List.copyOf(statuses);
        }
    }

    /**
     * What discovery saw for one product.
     *
     * @param platformId    which product this is ({@link EmulatorPlatform#id()})
     * @param installed     whether the product appears installed at all
     * @param instanceCount how many instances discovery found
     * @param error         the failure kind if discovery threw for this product, else {@code null}
     */
    public record PlatformStatus(PlatformId platformId, boolean installed, int instanceCount, String error) {

        /** Whether discovery completed without throwing for this product. */
        public boolean ok() {
            return error == null;
        }

        /** The product's human name. */
        public String displayName() {
            return platformId.displayName();
        }

        /**
         * The one-line summary a picker shows for this product — "MuMu: installed · 2 instances configured",
         * "BlueStacks: not installed", "LDPlayer: scan error (IOException)". Lives here so every picker words
         * it identically.
         */
        public String statusLine() {
            if (!ok()) return displayName() + ": scan error (" + error + ")";
            if (!installed) return displayName() + ": not installed";
            if (instanceCount == 0) return displayName() + ": installed · no instances configured";
            return displayName() + ": installed · " + instanceCount
                    + (instanceCount == 1 ? " instance" : " instances") + " configured";
        }
    }
}
