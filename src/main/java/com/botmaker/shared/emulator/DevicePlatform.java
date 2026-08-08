package com.botmaker.shared.emulator;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>A physical Android phone or tablet</b>, discovered the two ways one can be reached. It is an
 * {@link EmulatorPlatform} rather than a new concept for the reason {@link PlatformId#WAYDROID}'s javadoc
 * already gives about a Linux container: from a bot's point of view it is exactly the same thing — an Android
 * surface reachable over ADB — and treating it as a separate concept would fork every picker, every capture
 * source and every launch path for no gain. Everything above {@link AdbDevice} is already generic, so a phone
 * needed a discovery path, not a stack.
 *
 * <p>Two sources, and neither of them scans anything:
 *
 * <ol>
 *   <li><b>A running adb server's device list</b> ({@link AdbTools#devices()}), minus the {@code emulator-*}
 *       serials the real products discover for themselves. This is the only route to a device on a
 *       <b>USB cable</b>, and to Android 11+ TLS wireless debugging. It needs no binary when a server is
 *       already up, and it is skipped entirely when one is not.</li>
 *   <li><b>Addresses the user configured</b>, for a phone in legacy {@code adb tcpip} mode, which
 *       {@link AdbDevice} dials directly with no server and no binary at all.</li>
 * </ol>
 *
 * <p><b>The network is never scanned.</b> Sweeping a LAN for open 5555s is slow, is indistinguishable from a
 * port scan to anything watching, and would attach this stack to whatever else on the subnet happens to
 * answer. A phone is either plugged in, or its address is stated.
 *
 * <p>{@link #isInstalled()} is true whenever a device could be reached at all, since there is no product to
 * install — the closest honest reading of the question for hardware someone owns.
 */
public final class DevicePlatform implements EmulatorPlatform {

    private static final PlatformId PLATFORM_ID = PlatformId.PHYSICAL;

    /**
     * Comma-separated {@code host:port} addresses of phones in {@code adb tcpip} mode, as a system property
     * or an environment variable — {@code -Dbotmaker.adb.devices=192.168.1.5:5555} /
     * {@code BOTMAKER_ADB_DEVICES}.
     *
     * <p>This is the whole of source (2) for now, and it is deliberately a plain knob: it makes the
     * no-binary-anywhere path usable and testable immediately, without deciding where a user's saved device
     * list should live. Studio's "Connect a phone…" dialog replaces it with real persistence.
     */
    public static final String ADDRESSES_PROPERTY = "botmaker.adb.devices";
    static final String ADDRESSES_ENV = "BOTMAKER_ADB_DEVICES";

    @Override
    public PlatformId id() {
        return PLATFORM_ID;
    }

    @Override
    public boolean isInstalled() {
        return !discover().isEmpty() || AdbTools.available();
    }

    @Override
    public List<EmulatorInstance> discover() {
        List<EmulatorInstance> found = new ArrayList<>(configured(configuredAddresses()));
        for (AdbTools.ServerDevice device : AdbTools.devices()) {
            if (device.emulator()) {
                continue;
            }
            EmulatorInstance instance = new EmulatorInstance(PLATFORM_ID, device.displayName(),
                    new AdbEndpoint.Server(device.serial()));
            if (!containsEndpoint(found, instance)) {
                found.add(instance);
            }
        }
        return List.copyOf(found);
    }

    /** The raw {@link #ADDRESSES_PROPERTY} value, property first then environment; {@code ""} when unset. */
    static String configuredAddresses() {
        String property = System.getProperty(ADDRESSES_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv(ADDRESSES_ENV);
        return env == null ? "" : env;
    }

    /**
     * Parses a comma-separated {@code host:port} list into instances. Pure and package-visible so the parse is
     * asserted without a device: a malformed entry is skipped rather than throwing, because this value comes
     * from a hand-typed knob and one typo must not cost the user every other phone in the list.
     */
    static List<EmulatorInstance> configured(String addresses) {
        List<EmulatorInstance> instances = new ArrayList<>();
        if (addresses == null || addresses.isBlank()) {
            return instances;
        }
        for (String entry : addresses.split(",")) {
            String trimmed = entry.trim();
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (port <= 0 || port > 65535) {
                continue;
            }
            AdbEndpoint endpoint = new AdbEndpoint.Tcp(trimmed.substring(0, colon), port);
            instances.add(new EmulatorInstance(PLATFORM_ID, endpoint.label(), endpoint));
        }
        return instances;
    }

    /**
     * Whether {@code candidate}'s address is already in {@code found}. A phone connected over the network is
     * named {@code ip:port} by the adb server — the same string a configured address produces — so without
     * this the two sources report one phone twice.
     */
    private static boolean containsEndpoint(List<EmulatorInstance> found, EmulatorInstance candidate) {
        for (EmulatorInstance existing : found) {
            if (existing.endpoint().equals(candidate.endpoint())) {
                return true;
            }
        }
        return false;
    }
}
