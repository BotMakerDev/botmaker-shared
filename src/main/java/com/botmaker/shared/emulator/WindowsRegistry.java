package com.botmaker.shared.emulator;

import com.botmaker.shared.Spawn;

import java.time.Duration;

/**
 * A tiny, best-effort reader for single Windows registry values via {@code reg query}. Emulator discovery uses
 * it to find a product's install/data directory; everything else (instance names, ADB ports) comes from config
 * files.
 *
 * <p>Never throws: a missing key, a non-Windows OS, or a {@code reg} that can't run all yield {@code null}.
 */
public final class WindowsRegistry {

    /** {@code reg query} answers instantly or not at all; discovery must not wait on the "not at all". */
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    private WindowsRegistry() {}

    /**
     * Reads {@code valueName} under {@code keyPath} (e.g. {@code HKLM\SOFTWARE\BlueStacks_nxt}), or
     * {@code null} if absent/unreadable. The value's data is returned trimmed.
     */
    public static String read(String keyPath, String valueName) {
        if (!isWindows()) {
            return null;
        }
        try {
            Spawn.Completed query = Spawn.run(QUERY_TIMEOUT, "reg", "query", keyPath, "/v", valueName);
            if (query == null) {
                return null; // a discovery probe that hangs would hang the whole scan
            }
            // A matching line looks like:  "    ValueName    REG_SZ    C:\Some\Path"
            for (String line : query.output().split("\\R")) {
                int typeIdx = indexOfRegType(line);
                if (line.contains(valueName) && typeIdx >= 0) {
                    String data = line.substring(typeIdx);
                    // strip the "REG_SZ"/"REG_DWORD"/… token, keep the rest
                    int firstSpace = data.indexOf("    ");
                    String value = (firstSpace >= 0) ? data.substring(firstSpace) : data;
                    return value.trim();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The first value that is neither null nor blank, or {@code null} if there is none. Products keep the same
     * setting under several keys depending on how they were installed (MSI vs. installer, 32- vs. 64-bit), so
     * discovery reads them all and takes the first that answers: {@code firstNonBlank(read(a, k), read(b, k))}.
     */
    public static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static int indexOfRegType(String line) {
        for (String type : new String[]{"REG_SZ", "REG_EXPAND_SZ", "REG_DWORD", "REG_MULTI_SZ"}) {
            int i = line.indexOf(type);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
