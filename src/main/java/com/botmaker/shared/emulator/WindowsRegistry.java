package com.botmaker.shared.emulator;

import com.botmaker.shared.platform.Os;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tiny, best-effort reader for Windows registry values. Emulator discovery uses it to find a product's
 * install/data directory; everything else (instance names, ADB ports) comes from config files.
 *
 * <p>Read through the registry API ({@link Advapi32Util}), not {@code reg query}: a discovery scan asks for a few
 * hundred values (every uninstall entry, see {@link InstallLocator}), and a process per value would take seconds.
 * Always the 64-bit view, so a key path names {@code WOW6432Node} itself when it means the 32-bit one.
 *
 * <p>Never throws: a missing key, a non-Windows OS, or a registry that can't be read all yield {@code null}
 * (or an empty list).
 */
public final class WindowsRegistry {

    private static final int VIEW = WinNT.KEY_WOW64_64KEY;

    private WindowsRegistry() {}

    /**
     * Reads {@code valueName} under {@code keyPath} (e.g. {@code HKLM\SOFTWARE\BlueStacks_nxt}), or
     * {@code null} if absent/unreadable. A string's data is returned trimmed; a number as its decimal text.
     */
    public static String read(String keyPath, String valueName) {
        return values(keyPath).get(valueName);
    }

    /**
     * Every text or number value under {@code keyPath}, trimmed, by name (names ignore case, as the registry's
     * do); empty when the key is missing or unreadable. Binary values are left out: a path or a port is never
     * one, and {@code "[B@1a2b"} is no path. A multi-string gives its first line.
     */
    public static Map<String, String> values(String keyPath) {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!Os.current().isWindows()) {
            return values;
        }
        try {
            Key key = Key.of(keyPath);
            if (key == null || !Advapi32Util.registryKeyExists(key.root(), key.path(), VIEW)) {
                return values;
            }
            // Read in the same view the existence check used.
            for (var entry : Advapi32Util.registryGetValues(key.root(), key.path(), VIEW).entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String[] lines && lines.length > 0) {
                    values.put(entry.getKey(), lines[0].trim());
                } else if (value instanceof String || value instanceof Number) {
                    values.put(entry.getKey(), value.toString().trim());
                }
            }
        } catch (Exception | LinkageError e) {
            // what was read before the failure is still right
        }
        return values;
    }

    /** The names of the keys directly under {@code keyPath}; empty when it is missing or unreadable. */
    public static List<String> subkeys(String keyPath) {
        if (!Os.current().isWindows()) {
            return List.of();
        }
        try {
            Key key = Key.of(keyPath);
            if (key == null || !Advapi32Util.registryKeyExists(key.root(), key.path(), VIEW)) {
                return List.of();
            }
            return List.of(Advapi32Util.registryGetKeys(key.root(), key.path(), VIEW));
        } catch (Exception | LinkageError e) {
            return List.of();
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

    /** A key path split into its hive and the rest; only the two hives discovery reads are known. */
    private record Key(WinReg.HKEY root, String path) {

        static Key of(String keyPath) {
            int slash = keyPath.indexOf('\\');
            if (slash < 0) {
                return null;
            }
            WinReg.HKEY root = switch (keyPath.substring(0, slash).toUpperCase(java.util.Locale.ROOT)) {
                case "HKLM", "HKEY_LOCAL_MACHINE" -> WinReg.HKEY_LOCAL_MACHINE;
                case "HKCU", "HKEY_CURRENT_USER" -> WinReg.HKEY_CURRENT_USER;
                default -> null;
            };
            return root == null ? null : new Key(root, keyPath.substring(slash + 1));
        }
    }
}
