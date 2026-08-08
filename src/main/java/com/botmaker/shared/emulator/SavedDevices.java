package com.botmaker.shared.emulator;

import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The phones a user has written down</b> — a persisted list of {@code host:port} addresses for devices in
 * {@code adb tcpip} mode, which {@link DevicePlatform} discovers alongside whatever a running adb server owns.
 *
 * <p>It replaces {@link DevicePlatform#ADDRESSES_PROPERTY}, the {@code -D} knob that made the no-binary path
 * usable before there was anywhere to save an answer. The knob still works and is still read — it is how a
 * test or a scripted run states an address without touching a user's file — but it is no longer the only way,
 * and the two are unioned rather than one shadowing the other.
 *
 * <p><b>This lives in shared, not in Studio, and that is the whole point.</b> A saved phone has to be found by
 * everything that resolves a device: Studio's pickers, yes, but also a generated bot at run time resolving
 * {@code emulator:<name>} through {@link Platforms#discoverAll()}. A list kept in Studio's own preferences
 * would be a phone the editor can see and the bot it generates cannot — the exact split the pickers exist to
 * avoid.
 *
 * <p>The format is a text file, one device per line, because a user who has to fix this by hand should be able
 * to. {@code host:port}, optionally followed by a tab and a name to show instead of the address:
 *
 * <pre>
 * # BotMaker devices
 * 192.168.1.5:5555	Pixel 7
 * 10.0.0.42:5555
 * </pre>
 *
 * <p>Every read is total: a malformed line is skipped, not thrown over, and an unreadable file reads as an
 * empty list. One bad line must not cost a user the rest of their phones, and discovery must never fail
 * because of a file.
 */
public final class SavedDevices {

    private SavedDevices() {}

    /** Overrides the file location outright — used by tests, which must never touch a real user's list. */
    public static final String FILE_PROPERTY = "botmaker.adb.devices.file";

    /** Separates the address from the optional display name. A tab, so a name may contain spaces. */
    private static final String SEPARATOR = "\t";

    /**
     * One remembered device.
     *
     * @param host the address as typed — a hostname or an IP, never resolved here
     * @param port the {@code adbd} port, conventionally 5555
     * @param name what to call it, or {@code ""} to fall back to the address
     */
    public record SavedDevice(String host, int port, String name) {

        public SavedDevice {
            host = host == null ? "" : host.trim();
            name = name == null ? "" : name.trim();
        }

        public SavedDevice(String host, int port) {
            this(host, port, "");
        }

        public AdbEndpoint endpoint() {
            return new AdbEndpoint.Tcp(host, port);
        }

        /** What a picker shows: the user's own name for it, else the address it was added by. */
        public String displayName() {
            return name.isBlank() ? endpoint().label() : name;
        }

        /** Whether this is a usable entry at all — a blank host or an out-of-range port never persists. */
        public boolean valid() {
            return !host.isBlank() && port > 0 && port <= 65535;
        }
    }

    /** Where the list is kept. See {@link #configDir()} for the per-OS location. */
    public static Path file() {
        String override = System.getProperty(FILE_PROPERTY);
        return override != null && !override.isBlank()
                ? Path.of(override)
                : configDir().resolve("devices.txt");
    }

    /**
     * The per-OS <em>config</em> directory — deliberately {@link UserDirs#config()} and not
     * {@link UserDirs#cache()}. A saved phone is not derivable from anything and cannot be rebuilt by
     * re-scanning, so a cache-cleaner that removes it would be removing the user's own data. (The per-OS
     * layout used to live here; it moved to {@code UserDirs} when downloaded tools needed the other half of
     * the same question answered.)
     */
    private static Path configDir() {
        return UserDirs.config();
    }

    /** Every saved device, in the order they were added. Empty when there is no file, and never throws. */
    public static List<SavedDevice> load() {
        Path file = file();
        try {
            return Files.isRegularFile(file)
                    ? parse(Files.readString(file, StandardCharsets.UTF_8))
                    : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Replaces the whole list. Returns whether it was written — a caller that shows the list should refresh
     * from {@link #load()} either way rather than assume its own copy landed.
     */
    public static boolean save(List<SavedDevice> devices) {
        Path file = file();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, render(devices), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Adds a device, or renames one already saved at that address.
     *
     * <p>Re-adding an address is a rename rather than a duplicate on purpose: the address is the identity —
     * {@link EmulatorInstance#identity()} keys on it — so two rows for one address would be two rows for one
     * phone, and the second could never be selected distinctly from the first.
     */
    public static boolean add(SavedDevice device) {
        if (device == null || !device.valid()) {
            return false;
        }
        List<SavedDevice> devices = new ArrayList<>(load());
        devices.removeIf(existing -> existing.endpoint().equals(device.endpoint()));
        devices.add(device);
        return save(devices);
    }

    /** Forgets the device at this address. Returns whether the list changed. */
    public static boolean remove(AdbEndpoint endpoint) {
        List<SavedDevice> devices = new ArrayList<>(load());
        if (!devices.removeIf(existing -> existing.endpoint().equals(endpoint))) {
            return false;
        }
        return save(devices);
    }

    /**
     * Parses the file's text. Pure and package-visible so the shapes below are asserted without a filesystem:
     * a comment, a bare address, an address with a name, and the malformed lines that must be skipped rather
     * than throw.
     */
    static List<SavedDevice> parse(String text) {
        List<SavedDevice> devices = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return devices;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int tab = trimmed.indexOf('\t');
            String address = (tab < 0 ? trimmed : trimmed.substring(0, tab)).trim();
            String name = tab < 0 ? "" : trimmed.substring(tab + 1).trim();
            SavedDevice device = parseAddress(address, name);
            if (device != null) {
                devices.add(device);
            }
        }
        return devices;
    }

    /**
     * One {@code host:port}, or null when it is not one. Splits on the <em>last</em> colon so a bracketed IPv6
     * literal keeps its own.
     *
     * <p>Public because this is also how a dialog validates what a user typed: parsing and accepting must be
     * the same code, or a form can accept an address the file then silently drops.
     */
    public static SavedDevice parseAddress(String address, String name) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        SavedDevice device = new SavedDevice(trimmed.substring(0, colon), port, name);
        return device.valid() ? device : null;
    }

    /** Renders the list back to the file's text, skipping anything that would not read back. */
    static String render(List<SavedDevice> devices) {
        StringBuilder text = new StringBuilder("# BotMaker devices — one host:port per line, optional <TAB>name\n");
        if (devices != null) {
            for (SavedDevice device : devices) {
                if (device == null || !device.valid()) {
                    continue;
                }
                text.append(device.endpoint().label());
                if (!device.name().isBlank()) {
                    text.append(SEPARATOR).append(device.name());
                }
                text.append('\n');
            }
        }
        return text.toString();
    }
}
