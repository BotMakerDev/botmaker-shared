package com.botmaker.shared.emulator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What {@code waydroid status} reported. The command prints tab-separated {@code Label:\tValue} lines whose
 * <em>set</em> depends on state — a stopped session prints two lines, a running one adds the container state,
 * the session user, the Wayland display and the container's IP:
 *
 * <pre>
 * Session:      RUNNING
 * Container:    RUNNING
 * Vendor type:  MAINLINE
 * IP address:   192.168.240.112
 * </pre>
 *
 * <p>So the parse is deliberately <b>total and field-agnostic</b>: every line becomes a key/value pair and the
 * accessors below read the ones we care about, returning a default when absent. A newer Waydroid that adds or
 * drops a line still parses, which matters because this text is a human-facing CLI output, not a contract.
 */
public record WaydroidStatus(Map<String, String> fields) {

    /** The container's ADB port. Fixed: Waydroid runs one Android with {@code adbd} on the standard port. */
    public static final int ADB_PORT = 5555;

    /**
     * Where the container is reachable before it has told us — Waydroid's default LXC bridge address. Used
     * only when the session is stopped, since the real address is in {@code waydroid status} once it is up.
     */
    public static final String DEFAULT_IP = "192.168.240.112";

    public WaydroidStatus {
        fields = Map.copyOf(fields);
    }

    /** Whether the Waydroid <em>session</em> (the Android userspace) is up. */
    public boolean sessionRunning() {
        return "RUNNING".equalsIgnoreCase(field("Session"));
    }

    /**
     * Whether the LXC container is up. A stopped session prints no {@code Container} line at all, so absence
     * reads as "not running" — the state that matters is only ever asserted positively.
     */
    public boolean containerRunning() {
        return "RUNNING".equalsIgnoreCase(field("Container"));
    }

    /** The container's IP, or {@link #DEFAULT_IP} when it hasn't announced one (session down). */
    public String ipAddress() {
        String ip = field("IP address");
        return ip == null || ip.isBlank() ? DEFAULT_IP : ip;
    }

    /** {@code MAINLINE} or {@code HALIUM_*}; null when the line is absent. */
    public String vendorType() {
        return field("Vendor type");
    }

    /** One reported field by its label (without the colon), or {@code null}. */
    public String field(String label) {
        return fields.get(label);
    }

    /**
     * Parses {@code waydroid status} output. Null or unparseable input yields an empty status whose every
     * predicate is false — "we could not tell" and "it is not running" are treated alike here on purpose,
     * because every caller's next move (offer to start it, run diagnostics) is the same for both.
     */
    public static WaydroidStatus parse(String output) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (output != null) {
            for (String line : output.split("\\R")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (!key.isEmpty()) {
                    fields.put(key, value);
                }
            }
        }
        return new WaydroidStatus(fields);
    }

    /** Runs {@code waydroid status} and parses it; an empty status when the command isn't usable. */
    public static WaydroidStatus read() {
        return parse(WaydroidCli.available() ? WaydroidCli.waydroid("status") : null);
    }
}
