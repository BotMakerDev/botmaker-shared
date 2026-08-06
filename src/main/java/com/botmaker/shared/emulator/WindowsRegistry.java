package com.botmaker.shared.emulator;

import com.botmaker.shared.Spawn;
import com.botmaker.shared.platform.Os;

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
        if (!Os.current().isWindows()) {
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

    /**
     * The value types {@code reg query} prints between the value name and the data — the token this parser
     * splits each line on. Closed and defined by {@code reg.exe}, not by us: {@code REG_BINARY} and the
     * {@code REG_QWORD}s are omitted because discovery only ever reads paths and ports, and a line carrying
     * one simply doesn't parse (the same outcome as before this was named).
     */
    private static final String[] VALUE_TYPES = {"REG_SZ", "REG_EXPAND_SZ", "REG_DWORD", "REG_MULTI_SZ"};

    private static int indexOfRegType(String line) {
        for (String type : VALUE_TYPES) {
            int i = line.indexOf(type);
            if (i >= 0) {
                return i;
            }
        }
        return -1;
    }
}
