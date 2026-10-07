package com.botmaker.shared.emulator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The registry of known {@link EmulatorPlatform}s and the one place to enumerate instances across all of
 * them. BlueStacks, LDPlayer, MEmu, MuMu and Gameloop all discover for real today (Gameloop is limited to
 * its single primary instance). Add a product by adding it to {@link #ALL}.
 *
 * <p>The list is <b>not</b> Windows-only: {@link WaydroidPlatform} is a Linux Android container and each
 * platform gates on its own OS, so a scan on either OS simply reports the products that can exist there.
 */
public final class Platforms {

    private Platforms() {}

    /**
     * All known platforms, real discovery first.
     *
     * <p>{@link DevicePlatform} is <b>last on purpose</b>: it is the generic "some Android surface over ADB"
     * catch-all, and {@link #dedupe} lets an earlier, specific product keep an address they both report. See
     * that method for the case where they do.
     */
    public static final List<EmulatorPlatform> ALL = List.of(
            new BlueStacksPlatform(),
            new LdPlayerPlatform(),
            new MemuPlatform(),
            new MuMuPlatform(),
            new GameloopPlatform(),
            new WaydroidPlatform(),
            new DevicePlatform());

    /** Every discovered instance across every platform. Never throws; empty if nothing is installed. */
    public static List<EmulatorInstance> discoverAll() {
        return discoverDetailed().instances();
    }

    private record Snapshot(long at, List<EmulatorInstance> instances) {}

    private static volatile Snapshot last;

    /**
     * {@link #discoverAll()}, or the last one when it is younger than {@code maxAge}. A picker checks every row's
     * liveness at once and a launch polls it, and each check needs every product's state; one scan answers
     * them all instead of one per row. Never throws.
     */
    public static synchronized List<EmulatorInstance> recent(Duration maxAge) {
        Snapshot snapshot = last;
        if (snapshot != null && System.currentTimeMillis() - snapshot.at() < maxAge.toMillis()) {
            return snapshot.instances();
        }
        return discoverAll();
    }

    /** How to add an instance to each installed product that can, in {@link #ALL}'s order. Never throws. */
    public static List<NewInstance> newInstances() {
        List<NewInstance> ways = new ArrayList<>();
        for (EmulatorPlatform platform : ALL) {
            try {
                NewInstance way = platform.newInstance();
                if (way != null) ways.add(way);
            } catch (Exception ignored) {
                // a product whose install can't be read offers nothing
            }
        }
        return List.copyOf(ways);
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
                String note = installed && found.isEmpty() ? platform.statusNote() : null;
                statuses.add(new PlatformStatus(platform.id(), installed, found.size(), null, note));
            } catch (Exception e) {
                statuses.add(new PlatformStatus(platform.id(), installed, 0, e.getClass().getSimpleName(), null));
            }
        }
        DiscoveryReport report = new DiscoveryReport(dedupe(all), List.copyOf(statuses));
        last = new Snapshot(System.currentTimeMillis(), report.instances());
        return report;
    }

    /**
     * Drops a {@link PlatformId#PHYSICAL} instance whose address a product already reported, keeping the product's.
     *
     * <p><b>The case this exists for is one phone reported twice.</b> A Waydroid container or a networked
     * emulator that the user has also run {@code adb connect} against appears both in its own product's
     * discovery and in the adb server's device list, under the identical {@code ip:port} name — two rows, one
     * device, and two different {@link EmulatorInstance#identity()} values so nothing downstream could tell.
     * The product wins because it knows things the generic path cannot: the product, and how to launch and stop it.
     *
     * <p><b>Two products on one address both stay.</b> They are two instances that cannot run at once — BlueStacks,
     * LDPlayer's first instance and GameLoop all ask for {@code 127.0.0.1:5555} — and dropping the later one hid
     * every emulator but the first from a machine that has several.
     */
    static List<EmulatorInstance> dedupe(List<EmulatorInstance> instances) {
        Set<String> products = new HashSet<>();
        for (EmulatorInstance instance : instances) {
            if (instance.platformId() != PlatformId.PHYSICAL) products.add(instance.endpoint());
        }
        List<EmulatorInstance> unique = new ArrayList<>();
        Set<String> phones = new HashSet<>();
        for (EmulatorInstance instance : instances) {
            boolean phone = instance.platformId() == PlatformId.PHYSICAL;
            if (!phone || (!products.contains(instance.endpoint()) && phones.add(instance.endpoint()))) {
                unique.add(instance);
            }
        }
        return List.copyOf(unique);
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
     * @param note          why an installed product has no instance, when it can say ({@link
     *                      EmulatorPlatform#statusNote()}), else {@code null}
     */
    public record PlatformStatus(PlatformId platformId, boolean installed, int instanceCount, String error,
                                 String note) {

        public PlatformStatus(PlatformId platformId, boolean installed, int instanceCount, String error) {
            this(platformId, installed, instanceCount, error, null);
        }

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
            if (instanceCount == 0) {
                return displayName() + ": installed · " + (note == null ? "no instances configured" : note);
            }
            return displayName() + ": installed · " + instanceCount
                    + (instanceCount == 1 ? " instance" : " instances") + " configured";
        }
    }
}
